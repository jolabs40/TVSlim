package net.jolabs40.tvslim.tvapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.installation.FailureCause
import net.jolabs40.tvslim.installation.ApkReview
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.installation.ApkRejection
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.installation.ApkSignature
import net.jolabs40.tvslim.installation.InstalledVersion
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.shell.CommandExecutor
import java.io.File
import java.io.IOException

/** State of the TV Slim app on the TV. */
enum class TvAppState { MISSING, UPDATE, NO_PERMISSION, UP_TO_DATE }

data class TvSituation(
    val installed: InstalledVersion? = null,
    /** `WRITE_SECURE_SETTINGS` granted; without it the boot guard cannot reapply anything. */
    val authorized: Boolean = false,
    /** Latest release, if GitHub answered. */
    val available: TvRelease? = null,
) {
    val state: TvAppState
        get() = when {
            installed == null -> TvAppState.MISSING
            available != null && available.versionCode > installed.versionCode -> TvAppState.UPDATE
            !authorized -> TvAppState.NO_PERMISSION
            else -> TvAppState.UP_TO_DATE
        }
}

/** Current installation step, for the progress bar. */
sealed interface TvStep {
    data object Checking : TvStep
    data class Downloading(val receivedBytes: Long, val total: Long) : TvStep
    data object Verification : TvStep
    data class Upload(val sent: Long, val total: Long) : TvStep
    data object Authorization : TvStep
    data object Guardian : TvStep
}

/** Why nothing was installed, or why the installation stopped midway. */
enum class TvReason {
    NOT_FOUND, NETWORK, TOO_BIG, CERTIFICATE, PACKAGE_NAME, ANDROID_TOO_OLD, TV_UNREACHABLE, INSTALLATION,
}

sealed interface TvResult {
    /**
     * Installed. [authorized]: `WRITE_SECURE_SETTINGS` granted. [guardian]: the boot guard confirmed it is on; false
     * with a TV app too old to know the command (1.0.0).
     */
    data class Succeeded(val version: String, val authorized: Boolean, val guardian: Boolean) : TvResult

    data class Failed(val reason: TvReason, val cause: FailureCause? = null, val detail: String = "") : TvResult
}

/**
 * Installs the TV app from the latest GitHub release in one go: download, check the APK is ours, install,
 * grant what only ADB can grant, launch it, and turn on its boot guard.
 *
 * Nothing is sent to the TV before the APK certificate matches [expectedFingerprint] (`certificateFingerprint`,
 * which CI also checks before publishing).
 */
class TvApp(
    private val executor: CommandExecutor,
    private val installation: ApkInstallation,
    private val engine: DebloatEngine,
    private val reader: RemoteReader,
    private val source: ReleaseSource,
    private val expectedFingerprint: String,
    /** Where the APK is kept while it is sent; the file is always deleted afterwards. */
    private val folder: File,
) {

    /** Reads what the TV has, compared with [available] (the latest release from [last], or null). */
    suspend fun situation(available: TvRelease?): TvSituation {
        val versions = executor.execute("dumpsys package $PACKAGE_NAME | grep -E '^ +version(Code|Name)='")
        val installed = ApkInstallation.installedVersion(versions.output)
        val authorized = installed != null && WRITE_SECURE_SETTINGS in reader.permissions(PACKAGE_NAME).granted
        return TvSituation(installed, authorized, available)
    }

    /** Latest TV app release on GitHub, or null when GitHub does not answer. */
    suspend fun last(): TvRelease? = try {
        TvReleaseChoice.choose(source.releases())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    suspend fun install(onStep: (TvStep) -> Unit = {}): TvResult {
        val apk = File(folder, LOCAL_NAME)
        try {
            onStep(TvStep.Checking)
            val release = try {
                TvReleaseChoice.choose(source.releases())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: IOException) {
                return TvResult.Failed(TvReason.NETWORK)
            } ?: return TvResult.Failed(TvReason.NOT_FOUND)

            try {
                source.download(release.url, apk, MAX_SIZE) { receivedBytes, total ->
                    onStep(TvStep.Downloading(receivedBytes, if (total > 0) total else release.size))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: DownloadTooLarge) {
                return TvResult.Failed(TvReason.TOO_BIG)
            } catch (_: IOException) {
                return TvResult.Failed(TvReason.NETWORK)
            }

            onStep(TvStep.Verification)
            val fingerprint = withContext(Dispatchers.IO) { ApkSignature.certificateFingerprint(apk) }
            if (fingerprint == null || !fingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                return TvResult.Failed(TvReason.CERTIFICATE, detail = fingerprint.orEmpty())
            }
            val chosen = when (val review = installation.examine(apk, release.fileName)) {
                is ApkReview.Ready -> review.apk
                is ApkReview.Rejected -> return TvResult.Failed(
                    when (review.rejection) {
                        ApkRejection.ANDROID_TOO_OLD -> TvReason.ANDROID_TOO_OLD
                        ApkRejection.TV_UNREACHABLE -> TvReason.TV_UNREACHABLE
                        else -> TvReason.PACKAGE_NAME
                    },
                )
            }
            if (chosen.manifest.packageName != PACKAGE_NAME) return TvResult.Failed(TvReason.PACKAGE_NAME, detail = chosen.manifest.packageName)

            when (val upload = installation.install(chosen) { sent, total -> onStep(TvStep.Upload(sent, total)) }) {
                is InstallationResult.Failed ->
                    return TvResult.Failed(TvReason.INSTALLATION, upload.cause, upload.detail)
                is InstallationResult.Succeeded -> Unit
            }

            return finishSetup(release.version, onStep)
        } finally {
            withContext(Dispatchers.IO) { apk.delete() }
        }
    }

    /** Runs the post-install steps (grant, launch, boot guard) for a TV app that is already installed. */
    suspend fun authorize(version: String, onStep: (TvStep) -> Unit = {}): TvResult = finishSetup(version, onStep)

    private suspend fun finishSetup(version: String, onStep: (TvStep) -> Unit): TvResult.Succeeded {
        onStep(TvStep.Authorization)
        val authorized = grant()

        // An app never opened stays in the stopped state and does not receive BOOT_COMPLETED, so the guard
        // would never run. Launch it only when stopped: an update keeps it out of that state, and opening it
        // in the foreground would interrupt whatever the TV is showing.
        onStep(TvStep.Guardian)
        if (isStopped(executor.execute(STATE_COMMAND).output)) executor.execute("am start -n $PACKAGE_NAME/.MainActivity")
        val guardian = executor.execute(GUARDIAN_COMMAND).output.contains("result=$GUARDIAN_ENABLED")
        return TvResult.Succeeded(version, authorized, guardian)
    }

    /**
     * Grants `WRITE_SECURE_SETTINGS` and, on Android 13+, `POST_NOTIFICATIONS` so the TV app can report drift.
     * Both go through the engine, so each is journaled with its undo command.
     */
    private suspend fun grant(): Boolean {
        val permissions = reader.permissions(PACKAGE_NAME)
        val writing = WRITE_SECURE_SETTINGS in permissions.granted ||
            engine.grantPermission(PACKAGE_NAME, WRITE_SECURE_SETTINGS, permissions.requested).succeeded
        val sdk = executor.execute("getprop ro.build.version.sdk").output.trim().toIntOrNull() ?: 0
        if (sdk >= 33 && POST_NOTIFICATIONS in permissions.requested && POST_NOTIFICATIONS !in permissions.granted) {
            engine.grantPermission(PACKAGE_NAME, POST_NOTIFICATIONS, permissions.requested)
        }
        return writing
    }

    companion object {
        const val PACKAGE_NAME = "net.jolabs40.tvslim"
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        /**
         * The TV app's receiver only accepts this broadcast from a holder of `WRITE_SECURE_SETTINGS` (the ADB
         * shell, not another app). It replies with result [GUARDIAN_ENABLED] once the guard is on.
         */
        const val ACTION_GUARDIAN = "net.jolabs40.tvslim.action.ACTIVER_GARDIEN"
        const val GUARDIAN_ENABLED = 1
        const val GUARDIAN_COMMAND = "am broadcast -a $ACTION_GUARDIAN -n $PACKAGE_NAME/.system.ActivationGardienReceiver"

        /** Package state for user 0: `User 0: ... stopped=false notLaunched=false ...`. */
        const val STATE_COMMAND = "dumpsys package $PACKAGE_NAME | grep -E '^ +User 0:'"

        /**
         * True if the app is stopped for user 0 (never opened, or force-stopped). Only user 0 counts: the TCL's
         * second profile, never opened, reports it stopped forever. Unreadable output counts as stopped: a needless
         * launch costs one screen, a missed one costs the guard.
         */
        fun isStopped(output: String): Boolean {
            val line = output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("User 0:") && "stopped=" in it }
                ?: return true
            return "stopped=true" in line || "notLaunched=true" in line
        }

        private const val LOCAL_NAME = "tvslim-tv.apk"

        /** The TV APK is about 1.3 MB; 50 MB leaves headroom without accepting anything. */
        const val MAX_SIZE = 50L * 1024 * 1024
    }
}
