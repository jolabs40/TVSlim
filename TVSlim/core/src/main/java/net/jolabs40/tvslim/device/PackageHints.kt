package net.jolabs40.tvslim.device

/**
 * What an app declares to the system that makes disabling it risky, most serious first. Each is read with
 * an intent query that answers for all packages at once.
 */
enum class SensitiveDeclaration {
    /** TV input service: tuner, HDMI inputs, a partner's channels. */
    TV_INPUT,

    /** Accessibility service: screen reader, hearing aid... */
    ACCESSIBILITY,

    /** Input method: disabling it can leave the TV without any keyboard. */
    KEYBOARD,

    /** Boot receiver: the app starts with the device. */
    BOOT,
}

/** What ADB reports about a package, to judge whether it is safe to touch. Read by [PackageHintsReading]. */
data class PackageHints(
    /** The factory APK, on the system partition even when an update overrides it. */
    val path: String = "",
    /** A newer version is installed over the factory one, in /data/app. */
    val updated: Boolean = false,
    /** Linux UID the app runs under; null when Android did not report it. */
    val uid: Int? = null,
    val declarations: Set<SensitiveDeclaration> = emptySet(),
    /** Has an entry in the app menu, Android TV's or the classic one. */
    val icon: Boolean = false,
) {
    /** Partition and folder (`system_ext/app`, `product/priv-app`, `data/app`) rather than the full path. */
    val location: String
        get() {
            val folders = path.split('/').filter { it.isNotEmpty() }.dropLast(1)
            val index = folders.indexOfFirst { it in APP_FOLDERS }
            return (if (index >= 0) folders.take(index + 1) else folders.take(2)).joinToString("/")
        }

    /** Installed in a `priv-app` folder: Android grants it permissions it denies to other apps. */
    val privileged: Boolean get() = "/priv-app/" in path

    /** Runs as the system user (UID 1000): whatever it does, the system does. */
    val hasSystemPrivileges: Boolean get() = uid == SYSTEM_UID

    /** A UID reserved for the platform (system, telephony, Bluetooth, NFC) rather than for an app. */
    val reservedUid: Boolean get() = uid != null && uid < FIRST_APPLICATION_UID

    companion object {
        const val SYSTEM_UID = 1000
        const val FIRST_APPLICATION_UID = 10_000

        private val APP_FOLDERS = setOf("app", "priv-app", "overlay", "framework")
    }
}

/**
 * Reads the indices of every package in one read-only command: paths and UIDs, factory paths, one intent
 * query per sensitive declaration, then menu icons. Under a second for 77 KB of output on the TCL.
 */
object PackageHintsReading {

    const val FILES_MARKER = "@@TVSLIM_FILES"

    /** Factory version of an updated app; without it only the /data/app copy would show. */
    const val FACTORY_MARKER = "@@TVSLIM_FACTORY"
    const val ICONS_MARKER = "@@TVSLIM_ICONS"

    fun marker(declaration: SensitiveDeclaration): String = "@@TVSLIM_${declaration.name}"

    /** `MATCH_DISABLED_COMPONENTS`: a disabled package still declares what it would do once re-enabled. */
    private const val MATCH_DISABLED_COMPONENTS = 0x200

    private val QUERIES: Map<SensitiveDeclaration, List<String>> = mapOf(
        SensitiveDeclaration.TV_INPUT to listOf(services("android.media.tv.TvInputService")),
        SensitiveDeclaration.ACCESSIBILITY to listOf(services("android.accessibilityservice.AccessibilityService")),
        SensitiveDeclaration.KEYBOARD to listOf(services("android.view.InputMethod")),
        SensitiveDeclaration.BOOT to listOf(
            receivers("android.intent.action.BOOT_COMPLETED"),
            receivers("android.intent.action.LOCKED_BOOT_COMPLETED"),
        ),
    )

    val COMMAND: String = buildList {
        add("echo $FILES_MARKER")
        add("pm list packages -f -U")
        add("echo $FACTORY_MARKER")
        add("pm list packages -f --factory-only")
        QUERIES.forEach { (declaration, queries) ->
            add("echo ${marker(declaration)}")
            addAll(queries)
        }
        add("echo $ICONS_MARKER")
        add(menu("android.intent.category.LEANBACK_LAUNCHER"))
        add(menu("android.intent.category.LAUNCHER"))
    }.joinToString("; ")

    fun parse(output: String): Map<String, PackageHints> {
        val sections = RemoteReader.splitSections(output)
        val inPlace = files(sections[FILES_MARKER])
        val factory = files(sections[FACTORY_MARKER])
        val declarers = SensitiveDeclaration.entries.associateWith { componentPackages(sections[marker(it)]) }
        val icons = componentPackages(sections[ICONS_MARKER])

        return (inPlace.keys + factory.keys).associateWith { packageName ->
            val current = inPlace[packageName]
            val factoryFile = factory[packageName]
            PackageHints(
                path = factoryFile?.path ?: current?.path.orEmpty(),
                updated = factoryFile != null && current != null && current.path != factoryFile.path,
                uid = current?.uid,
                declarations = SensitiveDeclaration.entries.filterTo(mutableSetOf()) { packageName in declarers.getValue(it) },
                icon = packageName in icons,
            )
        }
    }

    private data class FileItem(val path: String, val uid: Int?)

    /** `package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard uid:1000`, the uid only with `-U`. */
    private fun files(lines: List<String>?): Map<String, FileItem> =
        lines.orEmpty()
            .mapNotNull { FILE_LINE.matchEntire(it) }
            .associate { it.groupValues[2] to FileItem(it.groupValues[1], it.groupValues[3].toIntOrNull()) }

    /** Packages named by the components of a `--brief` output, like `com.tcl.tvinput/.TunerInputService`. */
    private fun componentPackages(lines: List<String>?): Set<String> =
        lines.orEmpty().mapNotNull { COMPONENT.matchEntire(it)?.groupValues?.get(1) }.toSet()

    private fun services(action: String) =
        "cmd package query-services --brief --query-flags $MATCH_DISABLED_COMPONENTS -a $action"

    private fun receivers(action: String) =
        "cmd package query-receivers --brief --query-flags $MATCH_DISABLED_COMPONENTS -a $action"

    private fun menu(category: String) =
        "cmd package query-activities --brief --query-flags $MATCH_DISABLED_COMPONENTS -a android.intent.action.MAIN -c $category"

    /**
     * The path runs up to the last `=`: /data/app paths contain some (`~~taEQ...Xw==/`), package names never
     * do. One UID per user (`uid:10118,1010118`); the first is enough.
     */
    private val FILE_LINE = Regex("""package:(.+)=([A-Za-z0-9_.]+)(?:\s+uid:(\d+)\S*)?""")

    private val COMPONENT = Regex("""([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+""")
}
