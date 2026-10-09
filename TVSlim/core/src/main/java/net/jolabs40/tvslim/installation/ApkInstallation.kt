package net.jolabs40.tvslim.installation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ApkInstaller
import net.jolabs40.tvslim.shell.ShellResult
import java.io.File

/** Version of an app already on the TV. */
data class InstalledVersion(val versionCode: Long, val versionName: String)

/** What the installation will do, compared with what the TV already has. */
enum class InstallationKind { NEW, UPDATE, REINSTALLATION, DOWNGRADE }

/** A checked APK, ready for confirmation. */
data class ChosenApk(
    val file: File,
    /** File name as picked by the user; on Android, [file] is only a copy. */
    val name: String,
    val size: Long,
    val manifest: ApkManifest,
    val installed: InstalledVersion?,
) {
    val kind: InstallationKind
        get() = when {
            installed == null -> InstallationKind.NEW
            manifest.versionCode > installed.versionCode -> InstallationKind.UPDATE
            manifest.versionCode == installed.versionCode -> InstallationKind.REINSTALLATION
            else -> InstallationKind.DOWNGRADE
        }
}

/** Why a file is rejected before anything is sent. */
enum class ApkRejection { NOT_AN_APK, BATCH, INVALID_PACKAGE, ANDROID_TOO_OLD, TV_UNREACHABLE }

sealed interface ApkReview {
    data class Ready(val apk: ChosenApk) : ApkReview

    data class Rejected(val rejection: ApkRejection, val minSdk: Int? = null, val tvSdk: Int? = null) : ApkReview
}

/** The most common Android install failures, so they can be explained plainly. Each app words them. */
enum class FailureCause {
    SIGNATURE_MISMATCH, DOWNGRADE, ANDROID_TOO_OLD, ARCHITECTURE, INSUFFICIENT_STORAGE,
    UNSIGNED, INCOMPLETE, REJECTED, INVALID, CONNECTION, OTHER,
}

sealed interface InstallationResult {
    val apk: ChosenApk

    data class Succeeded(override val apk: ChosenApk) : InstallationResult

    /** [detail]: the TV's raw answer. */
    data class Failed(override val apk: ChosenApk, val cause: FailureCause, val detail: String) : InstallationResult
}

/**
 * Installs a user-picked APK on the TV, like `adb install`, with no PC needed for the companion and no
 * `adb.exe` for Windows.
 *
 * Two steps, like every TV Slim action: [examine] reads the file and the TV without changing anything, so
 * the confirmation can say which app arrives and what it replaces; [install] sends it, then journals it.
 * No undo command is recorded: the only one would be `pm uninstall`, which TV Slim never sends.
 */
class ApkInstallation(
    private val executor: CommandExecutor,
    private val installer: ApkInstaller,
    private val journal: JournalRepository,
) {

    suspend fun examine(file: File, name: String): ApkReview =
        when (val analysis = withContext(Dispatchers.IO) { ApkFile.analyze(file) }) {
            is ApkAnalysis.Valid -> examine(file, name, analysis.manifest)
            ApkAnalysis.Batch -> ApkReview.Rejected(ApkRejection.BATCH)
            ApkAnalysis.NotAnApk -> ApkReview.Rejected(ApkRejection.NOT_AN_APK)
        }

    /** Checks an already-read manifest against the TV: its Android version, and the version installed. */
    internal suspend fun examine(file: File, name: String, manifest: ApkManifest): ApkReview {
        // The package name goes into a command and comes from a file anyone could have crafted.
        if (!IDENTIFIER.matches(manifest.packageName)) return ApkReview.Rejected(ApkRejection.INVALID_PACKAGE)

        val reading = executor.execute(
            "getprop ro.build.version.sdk; dumpsys package ${manifest.packageName} | grep -E '^ +version(Code|Name)='",
        )
        // The exit code is grep's, 1 when the app is absent: only a broken connection counts here.
        if (reading.code < 0) return ApkReview.Rejected(ApkRejection.TV_UNREACHABLE)

        val sdk = reading.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
        val minSdk = manifest.minSdk
        if (sdk != null && minSdk != null && minSdk > sdk) {
            return ApkReview.Rejected(ApkRejection.ANDROID_TOO_OLD, minSdk = minSdk, tvSdk = sdk)
        }
        return ApkReview.Ready(
            ChosenApk(
                file = file,
                name = name,
                size = file.length(),
                manifest = manifest,
                installed = installedVersion(reading.output),
            ),
        )
    }

    suspend fun install(
        apk: ChosenApk,
        onSent: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): InstallationResult {
        val output = installer.install(apk.file, onSent)
        val succeeded = output.succeeded && output.output.contains("Success")
        // Empty when the TV said nothing; the typed cause is then enough for the UI.
        val detail = output.output

        val version = apk.manifest.versionName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        journal.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.INSTALLATION,
                target = apk.manifest.packageName,
                label = "Installation de ${apk.name}$version",
                undoCommand = "",
                succeeded = succeeded,
                message = if (succeeded) "" else detail,
            ),
        )
        return if (succeeded) {
            InstallationResult.Succeeded(apk)
        } else {
            InstallationResult.Failed(apk, failureCause(output), detail)
        }
    }

    internal companion object {
        /** The TV shell takes the line as is, so only a plain identifier is allowed. */
        private val IDENTIFIER = Regex("""[A-Za-z0-9_.]+""")

        private val VERSION_CODE = Regex("""versionCode=(\d+)""")
        private val VERSION_NAME = Regex("""versionName=(.*)""")
        private val CODE_ANDROID = Regex("""INSTALL_[A-Z_]+""")

        /**
         * The first version found is the installed one: for an updated system app, `dumpsys` then lists
         * the factory version hidden behind it.
         */
        fun installedVersion(output: String): InstalledVersion? {
            val code = VERSION_CODE.find(output)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val name = VERSION_NAME.find(output)?.groupValues?.get(1)?.trim().orEmpty()
            return InstalledVersion(code, name)
        }

        fun failureCause(output: ShellResult): FailureCause {
            if (output.code < 0) return FailureCause.CONNECTION
            val code = CODE_ANDROID.find(output.output)?.value ?: return FailureCause.OTHER
            return when (code) {
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
                "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES",
                -> FailureCause.SIGNATURE_MISMATCH

                "INSTALL_FAILED_VERSION_DOWNGRADE" -> FailureCause.DOWNGRADE
                "INSTALL_FAILED_OLDER_SDK" -> FailureCause.ANDROID_TOO_OLD
                "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> FailureCause.ARCHITECTURE
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> FailureCause.INSUFFICIENT_STORAGE
                "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> FailureCause.UNSIGNED
                "INSTALL_FAILED_MISSING_SPLIT" -> FailureCause.INCOMPLETE

                // Play Protect, a verification that does not complete, or a refusal on the remote.
                "INSTALL_FAILED_VERIFICATION_FAILURE",
                "INSTALL_FAILED_VERIFICATION_TIMEOUT",
                "INSTALL_FAILED_ABORTED",
                "INSTALL_FAILED_USER_RESTRICTED",
                -> FailureCause.REJECTED

                "INSTALL_FAILED_INVALID_APK" -> FailureCause.INVALID
                else -> if (code.startsWith("INSTALL_PARSE_FAILED")) FailureCause.INVALID else FailureCause.OTHER
            }
        }
    }
}
