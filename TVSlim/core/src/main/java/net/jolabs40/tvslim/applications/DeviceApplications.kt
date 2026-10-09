package net.jolabs40.tvslim.applications

import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.engine.EngineReason
import net.jolabs40.tvslim.engine.NameKind
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.engine.reasonIfSilent
import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** An app on the device: either in the launcher menu, or installed by the user. */
class DeviceApplication(
    val packageName: String,
    val versionCode: Long,
    /** Shipped with the device (updates included); false when installed by the user. */
    val system: Boolean,
    val active: Boolean,
    /** Launch activity (`package/.Activity`), or `null` when the app has no launcher icon. */
    val launch: String?,
    /** Display name; the package name until it has been read. */
    val name: String,
    /** PNG icon; `null` until it has been read. */
    val icon: ByteArray?,
) {
    val loaded: Boolean get() = icon != null

    fun withDetails(name: String, icon: ByteArray?): DeviceApplication =
        DeviceApplication(packageName, versionCode, system, active, launch, name, icon)
}

/** Name and icon of an app, as returned by the helper. */
class ApplicationDetails(val name: String, val icon: ByteArray)

/** Caches names and icons; an app is read again only when its version code changes. */
interface ApplicationsCache {
    fun read(packageName: String, versionCode: Long): ApplicationDetails?

    fun write(packageName: String, versionCode: Long, details: ApplicationDetails)
}

/**
 * On-disk cache, one `.png` and one `.name` file per package and version, fronted by an in-memory cache so the disk
 * is read once per session. Safe to delete: everything is read again.
 */
class FileCacheApplications(private val folder: File) : ApplicationsCache {

    private val memory = ConcurrentHashMap<String, ApplicationDetails>()

    override fun read(packageName: String, versionCode: Long): ApplicationDetails? {
        val key = key(packageName, versionCode)
        memory[key]?.let { return it }
        val icon = File(folder, "$key.png")
        val name = File(folder, "$key.nom")
        if (!icon.isFile || !name.isFile) return null
        return runCatching { ApplicationDetails(name.readText(Charsets.UTF_8), icon.readBytes()) }
            .getOrNull()
            ?.also { memory[key] = it }
    }

    override fun write(packageName: String, versionCode: Long, details: ApplicationDetails) {
        val key = key(packageName, versionCode)
        memory[key] = details
        runCatching {
            folder.mkdirs()
            File(folder, "$key.png").writeBytes(details.icon)
            File(folder, "$key.nom").writeText(details.name, Charsets.UTF_8)
        }
    }

    private fun key(packageName: String, versionCode: Long) = "$packageName@$versionCode"
}

/** Why the app list could not be read; each app localizes the message. */
enum class ReadCause {
    /** The helper is missing from the app's resources (incomplete build). */
    HELPER_MISSING,

    /** The device rejected the helper, or the connection dropped while pushing it. */
    UPLOAD,

    /** Unexpected helper output: `app_process` refused, or a version mismatch. */
    HELPER_REJECTED,

    CONNECTION,
}

sealed interface ReadResult {
    data class Read(val applications: List<DeviceApplication>) : ReadResult

    data class Failure(val cause: ReadCause, val detail: String) : ReadResult
}

/**
 * Lists the device's apps with their name and icon, which no ADB command provides.
 *
 * A small helper (`helper/`, a few KB, bundled in the resources) is pushed to `/data/local/tmp`, run with
 * `app_process` as the shell user (the way scrcpy runs its server), then deleted. Nothing is installed.
 *
 * Two passes: the list (1.6 s on the TCL), then names and icons in batches, only for what [cache] lacks. A phone
 * with 200 apps takes about 30 s the first time.
 */
class ApplicationsReader(
    private val executor: CommandExecutor,
    private val uploader: FileUploader,
    private val helper: () -> InputStream?,
    private val cache: ApplicationsCache,
) {

    /**
     * Reads the list, then fills in names and icons. [onProgress] receives the list after each step, with the number
     * of details read so far and the total to read.
     */
    suspend fun read(onProgress: (List<DeviceApplication>, done: Int, total: Int) -> Unit = { _, _, _ -> }): ReadResult {
        val bytes = runCatching { helper()?.use { it.readBytes() } }.getOrNull()
            ?: return ReadResult.Failure(ReadCause.HELPER_MISSING, RESOURCE_PATH)

        val upload = uploader.send(ByteArrayInputStream(bytes), bytes.size.toLong(), HELPER_PATH, System.currentTimeMillis(), { false }, {})
        if (upload.code != 0) return ReadResult.Failure(if (upload.code < 0) ReadCause.CONNECTION else ReadCause.UPLOAD, upload.output)

        try {
            val response = executor.execute(command("liste"))
            if (response.code < 0) return ReadResult.Failure(ReadCause.CONNECTION, response.output)
            if (!isRecognizedVersion(response.output)) return ReadResult.Failure(ReadCause.HELPER_REJECTED, excerpt(response.output))

            var applications = sort(readList(response.output).map { app ->
                cache.read(app.packageName, app.versionCode)?.let { app.withDetails(it.name, it.icon) } ?: app
            })
            val toRead = applications.filterNot { it.loaded }
            onProgress(applications, 0, toRead.size)

            var done = 0
            for (batch in toRead.chunked(BATCH)) {
                val details = executor.execute(command("details $ICON_SIZE " + batch.joinToString(" ") { it.packageName }))
                if (details.code < 0) return ReadResult.Failure(ReadCause.CONNECTION, details.output)
                val readResult = readDetails(details.output)
                applications = sort(applications.map { app ->
                    val justRead = readResult[app.packageName] ?: return@map app
                    cache.write(app.packageName, app.versionCode, justRead)
                    app.withDetails(justRead.name, justRead.icon)
                })
                done += batch.size
                onProgress(applications, done, toRead.size)
            }
            return ReadResult.Read(applications)
        } finally {
            executor.execute("rm -f $HELPER_PATH")
        }
    }

    companion object {
        /** Helper location in the resources of both apps. */
        const val RESOURCE_PATH = "aide/tvslim-aide.apk"
        const val HELPER_PATH = "/data/local/tmp/tvslim-aide.apk"
        const val HELPER_CLASS = "net.jolabs40.tvslim.aide.Aide"
        const val HELPER_VERSION = 1

        /** 96 px: sharp at 48 dp on a 2x screen, about 4 KB per icon. */
        const val ICON_SIZE = 96

        /** Small enough to fit in the command timeout, even on a phone slow to load app resources. */
        const val BATCH = 12

        private val IDENTIFIER = Regex("""[A-Za-z0-9_.]+""")

        fun command(arguments: String) = "CLASSPATH=$HELPER_PATH app_process / $HELPER_CLASS $arguments"

        internal fun isRecognizedVersion(output: String): Boolean =
            output.lineSequence().any { it.trim() == "TVSLIM_AIDE $HELPER_VERSION" }

        /** Parses tab-separated `A package versionCode system enabled launch` lines; other lines are ignored. */
        internal fun readList(output: String): List<DeviceApplication> = output.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("A\t") }
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size < 6 || !IDENTIFIER.matches(fields[1])) return@mapNotNull null
                DeviceApplication(
                    packageName = fields[1],
                    versionCode = fields[2].toLongOrNull() ?: 0L,
                    system = fields[3] == "1",
                    active = fields[4] == "1",
                    launch = fields[5].takeIf { it != "-" && it.contains('/') },
                    name = fields[1],
                    icon = null,
                )
            }
            .toList()

        /** Parses `D package name icon` lines; an unreadable line is skipped and its app keeps the package as name. */
        internal fun readDetails(output: String): Map<String, ApplicationDetails> = output.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.startsWith("D\t") }
            .mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size < 4) return@mapNotNull null
                val icon = runCatching { Base64.getDecoder().decode(fields[3]) }.getOrNull() ?: return@mapNotNull null
                fields[1] to ApplicationDetails(fields[2].ifBlank { fields[1] }, icon)
            }
            .toMap()

        /** Sorts by name, case-insensitively. */
        fun sort(applications: List<DeviceApplication>): List<DeviceApplication> =
            applications.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        private fun excerpt(output: String) = output.lines().filter { it.isNotBlank() }.take(3).joinToString(" ")
    }
}

/** Actions from the Applications tab. Disabling is not here: it goes through the debloat engine. */
class ApplicationsActions(
    private val executor: CommandExecutor,
    private val journal: () -> JournalRepository?,
) {

    /** Launches the app on the device, as from the launcher. */
    suspend fun open(application: DeviceApplication): ActionResult {
        val launch = application.launch
        if (launch == null || !COMPONENT.matches(launch)) {
            return ActionResult(application.packageName, application.name, false, reason = EngineReason.NoActivity)
        }
        // Single-quoted: nested activity class names contain `$`, which the shell would expand.
        val output = executor.execute("am start -n '$launch'")
        val succeeded = output.succeeded && !output.output.contains("Error")
        val message = if (succeeded) "" else output.output.trim()
        return ActionResult(application.packageName, application.name, succeeded, message, reasonIfSilent(succeeded, message))
    }

    /**
     * Uninstalls an app the user installed, re-checked right before with `pm list packages -3`. System packages are
     * never uninstalled; the debloat engine only uses `disable-user`. Cannot be undone, and is logged to the journal.
     */
    suspend fun uninstall(application: DeviceApplication): ActionResult {
        val packageName = application.packageName
        if (!IDENTIFIER.matches(packageName)) {
            return ActionResult(packageName, application.name, false, reason = EngineReason.InvalidName(NameKind.PACKAGE_NAME, packageName))
        }
        val thirdParty = executor.execute("pm list packages -3 --user 0 $packageName")
        if (thirdParty.code < 0) return ActionResult(packageName, application.name, false, thirdParty.output)
        if (thirdParty.output.lines().none { it.trim() == "package:$packageName" }) {
            return ActionResult(packageName, application.name, false, reason = EngineReason.NotInstalledByUser)
        }
        val output = executor.execute("pm uninstall $packageName")
        val succeeded = output.succeeded && output.output.contains("Success")
        val message = if (succeeded) "" else output.output.trim()
        journal()?.add(
            listOf(
                JournalAction(
                    timestamp = System.currentTimeMillis(),
                    type = ActionType.UNINSTALLATION,
                    target = packageName,
                    label = application.name,
                    undoCommand = "",
                    succeeded = succeeded,
                    message = message,
                ),
            ),
        )
        return ActionResult(packageName, application.name, succeeded, message, reasonIfSilent(succeeded, message))
    }

    private companion object {
        val IDENTIFIER = Regex("""[A-Za-z0-9_.]+""")
        val COMPONENT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+""")
    }
}
