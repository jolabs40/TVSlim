package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.shell.CommandExecutor

/** Result of a single query of the TV. */
data class DeviceSnapshot(
    val info: DeviceInfo = DeviceInfo.EMPTY,
    val states: Map<String, PackageState> = emptyMap(),
    /**
     * Every package shipped with the device (all but user-installed ones), enabled or disabled. Empty when
     * the third-party list is missing: without it, an installed app would pass for a manufacturer package.
     */
    val systemPackages: Map<String, PackageState> = emptyMap(),
    /** Packages with an icon in a phone's app menu (`category.LAUNCHER`), disabled ones included. */
    val applicationsMenu: Set<String> = emptySet(),
)

/**
 * Reads a TV's state over the shell, read-only (the TV app reads it locally through `PackageManager`).
 * The snapshot is a single command to save network round trips, split into sections by `@@TVSLIM_` markers.
 */
class RemoteReader(private val executor: CommandExecutor) {

    suspend fun snapshot(
        watchedPackages: Collection<String>,
        homePackages: Set<String>,
    ): DeviceSnapshot {
        val output = executor.execute(COMMAND)
        if (!output.succeeded) return DeviceSnapshot()

        val sections = splitSections(output.output)
        val disabled = packages(sections[DISABLED_MARKER])
        val active = packages(sections[ACTIVE_MARKER])
        val properties = sections[PROPERTIES_MARKER].orEmpty().map { it.trim() }
        val memory = memory(sections[MEMORY_MARKER])
        // Null rather than empty when the section is missing: "no third-party app" would be wrong.
        val thirdParty = sections[THIRD_PARTY_MARKER]?.let(::packages)

        return DeviceSnapshot(
            info = DeviceInfo(
                brand = properties.getOrElse(0) { "" },
                retailBrand = sections[BRAND_MARKER].orEmpty().firstOrNull()?.trim().orEmpty(),
                model = properties.getOrElse(1) { "" },
                androidVersion = properties.getOrElse(2) { "" },
                build = properties.getOrElse(3) { "" },
                totalMemoryMb = memory.first,
                freeMemoryMb = memory.second,
                installedPackages = active.size,
                disabledPackages = disabled.size,
                currentHome = home(sections[HOME_MARKER]),
                homeComponent = homeComponent(sections[HOME_MARKER]),
                thirdPartyLaunchers = launchers(sections[LAUNCHERS_MARKER], homePackages),
                factoryHomes = factoryHomes(
                    lines = sections[ALL_HOMES_MARKER],
                    thirdParty = thirdParty,
                    homePackages = homePackages,
                    disabled = disabled,
                    active = active,
                ),
                characteristics = sections[CHARACTERISTICS_MARKER].orEmpty().firstOrNull()?.trim().orEmpty(),
                // Null when the section is missing: "no declared feature" would mean "no touchscreen".
                features = sections[FEATURES_MARKER]?.let(::features),
            ),
            states = watchedPackages.associateWith { packageName ->
                when (packageName) {
                    in disabled -> PackageState.DISABLED
                    in active -> PackageState.ACTIVE
                    else -> PackageState.ABSENT
                }
            },
            systemPackages = thirdParty?.let { installed ->
                (active.associateWith { PackageState.ACTIVE } + disabled.associateWith { PackageState.DISABLED })
                    .filterKeys { it !in installed }
            }.orEmpty(),
            applicationsMenu = applicationsMenu(sections[MENU_MARKER].orEmpty()),
        )
    }

    /**
     * Memory breakdown and per-process usage, kept out of the snapshot because `dumpsys meminfo` is heavy.
     * Uses PSS, the only measure that does not count shared memory twice.
     */
    suspend fun memory(): MemoryBreakdown {
        val output = executor.execute("dumpsys meminfo")
        if (!output.succeeded) return MemoryBreakdown()

        val processes = mutableListOf<MemoryProcess>()
        var inList = false
        var total = 0L
        var free = 0L
        var used = 0L
        var cache = 0L
        var zram = 0L

        output.output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Total PSS by process") -> inList = true
                trimmed.startsWith("Total PSS by") -> inList = false
                trimmed.startsWith("Total RAM:") -> total = firstNumber(trimmed)
                trimmed.startsWith("Free RAM:") -> {
                    free = firstNumber(trimmed)
                    cache = CACHE.find(trimmed)?.let { parseNumber(it.groupValues[1]) } ?: 0L
                }
                trimmed.startsWith("Used RAM:") -> used = firstNumber(trimmed)
                trimmed.startsWith("ZRAM:") -> zram = firstNumber(trimmed)
                inList -> PROCESS.find(trimmed)?.let { found ->
                    processes += MemoryProcess(
                        name = found.groupValues[2],
                        pid = found.groupValues[3].toIntOrNull() ?: 0,
                        kilobytes = parseNumber(found.groupValues[1]),
                    )
                }
            }
        }

        return MemoryBreakdown(
            totalKb = total,
            freeKb = free,
            usedKb = used,
            cacheKb = cache,
            zramKb = zram,
            processes = processes.sortedByDescending { it.kilobytes },
        )
    }

    /** Internal storage usage and per-app size, on demand like [memory]. */
    suspend fun storage(): StorageBreakdown {
        val output = executor.execute(STORAGE_COMMAND)
        return if (output.succeeded) StorageReading.parse(output.output) else StorageBreakdown()
    }

    /**
     * Per-package indices for the unknown-packages inventory. The exit code is only the last query's, so
     * sections are parsed regardless of it.
     */
    suspend fun hints(): Map<String, PackageHints> =
        PackageHintsReading.parse(executor.execute(PackageHintsReading.COMMAND).output)

    /** Firmware identity for the unknown-packages inventory. */
    suspend fun firmware(): Firmware {
        val output = executor.execute(FirmwareReading.COMMAND)
        return if (output.succeeded) FirmwareReading.parse(output.output) else Firmware()
    }

    private fun parseNumber(raw: String): Long = raw.replace(",", "").trim().toLongOrNull() ?: 0L

    private fun firstNumber(line: String): Long =
        NUMBER.find(line)?.let { parseNumber(it.groupValues[1]) } ?: 0L

    /**
     * Current home package, read after `set-home-activity` to check it took effect: while the factory home
     * is enabled, Android answers `Success` and changes nothing. Empty on failure.
     */
    suspend fun currentHome(): String {
        val output = executor.execute(HOME_COMMAND)
        return if (output.succeeded) home(output.output.lines()) else ""
    }

    /** Cheap installed check, polled while waiting for an install. */
    suspend fun isInstalled(packageName: String): Boolean {
        val output = executor.execute("pm list packages --user 0 $packageName")
        return output.succeeded &&
            output.output.lineSequence().any { it.trim() == "package:$packageName" }
    }

    /**
     * Permissions an app requests and those it was granted, checked before `pm grant` so that a permission
     * missing from the manifest is rejected here with a message rather than on the TV with a Java exception.
     *
     * Parses the indented `dumpsys package` sections: `requested permissions:` (the manifest),
     * `install permissions:` and `runtime permissions:` (granted). `declared permissions:`, defined for other
     * apps, is ignored.
     */
    suspend fun permissions(packageName: String): PackagePermissions {
        if (!IDENTIFIER.matches(packageName)) return PackagePermissions()
        val output = executor.execute("dumpsys package $packageName")
        if (!output.succeeded) return PackagePermissions()

        val requested = mutableSetOf<String>()
        val granted = mutableSetOf<String>()
        var found = false
        var section = PermissionsSection.NONE

        output.output.lineSequence().forEach { line ->
            val trimmed = line.trim()

            // Section header: a line ending in ":" with nothing else on it. "User 0: ceDataInode=..."
            // is not one, even though it separates install permissions from runtime ones.
            if (trimmed.endsWith(":") && !trimmed.contains("granted=")) {
                val title = trimmed.lowercase()
                section = when {
                    title.startsWith("requested permissions") -> PermissionsSection.REQUESTED
                    title.startsWith("install permissions") ||
                        title.startsWith("runtime permissions") -> PermissionsSection.GRANTED

                    else -> PermissionsSection.NONE
                }
                // Only an installed package has these sections; for any other, `dumpsys` prints
                // "Unable to find package" and nothing else.
                if (section != PermissionsSection.NONE) found = true
                return@forEach
            }
            if (section == PermissionsSection.NONE) return@forEach

            // Skip anything that is not a permission name without leaving the section: `dumpsys`
            // inserts service lines there, and leaving early would miss the permissions after them.
            val match = PERMISSION_LINE.find(trimmed) ?: return@forEach
            val name = match.groupValues[1]
            if (section == PermissionsSection.REQUESTED) {
                requested += name
            } else if (match.groupValues[2].contains("granted=true")) {
                granted += name
            }
        }

        return PackagePermissions(packageFound = found, requested = requested, granted = granted)
    }

    /**
     * Mode of an app-op: `allow`, `ignore`, `deny` or `default`.
     *
     * `PACKAGE_USAGE_STATS` is also governed by the `GET_USAGE_STATS` app-op: at `default` the permission
     * decides, at `ignore` the app sees nothing despite a successful `pm grant`.
     *
     * Outputs seen on real hardware: `GET_USAGE_STATS: allow; time=...`, `No operations.` then
     * `Default mode: default`, or `Error: ...` when the package or op does not exist.
     */
    suspend fun appOpMode(packageName: String, appOp: String): String {
        if (!IDENTIFIER.matches(packageName) || !IDENTIFIER.matches(appOp)) return ""
        val output = executor.execute("cmd appops get $packageName $appOp")
        if (!output.succeeded) return ""

        var defaultMode = ""
        output.output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Error:") -> return ""
                trimmed.startsWith("$appOp:") ->
                    return trimmed.substringAfter(':').substringBefore(';').trim()

                trimmed.startsWith("Default mode:") -> defaultMode = trimmed.substringAfter(':').trim()
            }
        }
        return defaultMode
    }

    private fun packages(lines: List<String>?): Set<String> =
        lines.orEmpty().map { it.removePrefix("package:") }.filter { it.isNotBlank() }.toSet()

    /** Total and available memory in MB, from /proc/meminfo. */
    private fun memory(lines: List<String>?): Pair<Long, Long> {
        fun rawValue(key: String): Long = lines.orEmpty()
            .firstOrNull { it.startsWith(key) }
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?.div(1024)
            ?: 0L
        return rawValue("MemTotal") to rawValue("MemAvailable")
    }

    private fun home(lines: List<String>?): String =
        lines.orEmpty().lastOrNull { it.contains('/') }?.substringBefore('/').orEmpty()

    /** Full component of the current home, as `set-home-activity` needs it to restore it. */
    private fun homeComponent(lines: List<String>?): String =
        lines.orEmpty().lastOrNull { COMPONENT.matches(it) }.orEmpty()

    /**
     * Apps that can act as home screen, excluding the catalogue's factory homes. Used by the safeguard:
     * without a third-party launcher, the stock home must not be disabled.
     */
    private fun launchers(
        lines: List<String>?,
        homePackages: Set<String>,
    ): List<InstalledLauncher> =
        homeActivities(lines)
            .filterNot { it.packageName.isBlank() || it.packageName in homePackages }
            .filterNot { isSystemFallback(it.priority, it.component) }
            .map { InstalledLauncher(packageName = it.packageName, name = it.packageName, component = it.component) }
            .distinctBy { it.packageName }

    /**
     * Factory home screens, disabled ones included, for display only (the safeguard uses [launchers]).
     *
     * `query-activities` returns a disabled package only with `MATCH_DISABLED_COMPONENTS`, as seen on the TCL
     * with Google TV disabled. "Factory" means absent from `pm list packages -3`, minus fallback screens, setup
     * wizards and provisioning, which also answer HOME. Without the third-party list, only the catalogue's
     * homes are kept.
     */
    private fun factoryHomes(
        lines: List<String>?,
        thirdParty: Set<String>?,
        homePackages: Set<String>,
        disabled: Set<String>,
        active: Set<String>,
    ): List<FactoryHome> {
        val found = homeActivities(lines)
            // An Android that ignores the flag prints its help text, which contains "a/b" strings.
            .filter { COMPONENT.matches(it.component) }
            .filterNot { isSystemFallback(it.priority, it.component) || isAssistant(it.packageName) }
            .filter { activity -> if (thirdParty == null) activity.packageName in homePackages else activity.packageName !in thirdParty }
            .distinctBy { it.packageName }
            .map { FactoryHome(it.packageName, it.component, active = it.packageName !in disabled) }

        // Without an answer, the catalogue's homes present on the device are still listed by name.
        val fromCatalog = homePackages
            .filter { (it in disabled || it in active) && !isAssistant(it) }
            .filter { packageName -> found.none { it.packageName == packageName } }
            .map { FactoryHome(it, component = "", active = it !in disabled) }

        return found + fromCatalog
    }

    /** Each component in a `query-activities --brief` output, with the priority printed before it. */
    private fun homeActivities(lines: List<String>?): List<HomeActivity> {
        val matches = mutableListOf<HomeActivity>()
        var priority = 0

        lines.orEmpty().forEach { line ->
            PRIORITY.find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                priority = it
                return@forEach
            }
            if (!line.contains('/') || line.startsWith("Activity Resolver")) return@forEach
            matches += HomeActivity(priority, line.substringAfter(' ', line).trim())
        }
        return matches
    }

    /**
     * Setup wizards, provisioning, the system chooser and manufacturer dispatchers handle HOME without being
     * a home screen. On Philips, `org.droidtv.homeintentresolver` takes HOME at priority 100 and routes the
     * key elsewhere.
     */
    private fun isAssistant(packageName: String): Boolean =
        packageName == "android" || ASSISTANT.containsMatchIn(packageName)

    /**
     * `FallbackHome` answers `category.HOME` but only shows a blank screen during boot; treating it as a
     * replacement home would allow disabling the stock home and booting into nothing. Android gives it a
     * negative priority.
     */
    private fun isSystemFallback(priority: Int, component: String): Boolean =
        priority < 0 || component.contains("FallbackHome", ignoreCase = true)

    private enum class PermissionsSection { NONE, REQUESTED, GRANTED }

    private data class HomeActivity(val priority: Int, val component: String) {
        val packageName: String get() = component.substringBefore('/')
    }

    internal companion object {
        private val PRIORITY = Regex("""priority=(-?\d+)""")

        /** `package/.Activity`: a component and nothing else, in particular not a help line. */
        private val COMPONENT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.]+""")

        private val ASSISTANT = Regex("setup|provision|intentresolver", RegexOption.IGNORE_CASE)

        /** `MATCH_DISABLED_COMPONENTS`: without it, a disabled home does not exist for Android. */
        private const val MATCH_DISABLED_COMPONENTS = 0x200

        /** `android.permission.DUMP` alone, or followed by `: granted=true`. */
        private val PERMISSION_LINE =
            Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)(?::(.*))?$""")

        /** A package name and nothing else: the command goes to a shell. */
        private val IDENTIFIER = Regex("""[A-Za-z0-9_.]+""")

        /** `161,015K: com.spocky.projengmenu (pid 4799 state 14 oom 150 / activities)` */
        private val PROCESS = Regex("""^([\d,]+)K:\s+(\S+)\s+\(pid\s+(\d+)""")
        private val NUMBER = Regex("""([\d,]+)K""")
        private val CACHE = Regex("""([\d,]+)K cached pss""")

        /** Files each trimmed line under the last marker seen; blank lines are dropped. */
        internal fun splitSections(output: String): Map<String, List<String>> {
            val sections = mutableMapOf<String, MutableList<String>>()
            var current: MutableList<String>? = null
            output.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith(MARKER_PREFIX)) {
                    current = mutableListOf<String>().also { sections[trimmed] = it }
                } else if (trimmed.isNotBlank()) {
                    current?.add(trimmed)
                }
            }
            return sections
        }

        // Never `#`: a shell word starting with # comments out the rest of the line, i.e. the whole command.
        const val MARKER_PREFIX = "@@TVSLIM_"
        const val DISABLED_MARKER = "@@TVSLIM_D"
        const val ACTIVE_MARKER = "@@TVSLIM_E"
        const val PROPERTIES_MARKER = "@@TVSLIM_P"
        const val MEMORY_MARKER = "@@TVSLIM_M"
        const val HOME_MARKER = "@@TVSLIM_H"
        const val LAUNCHERS_MARKER = "@@TVSLIM_L"

        /**
         * Own section for the brand: an empty value in [PROPERTIES_MARKER] would shift its four lines, since
         * blank lines are dropped.
         */
        const val BRAND_MARKER = "@@TVSLIM_B"

        /** Home activities, disabled ones included, to find a disabled factory home. */
        const val ALL_HOMES_MARKER = "@@TVSLIM_U"

        /** Apps installed by the user; everything else came with the device. */
        const val THIRD_PARTY_MARKER = "@@TVSLIM_T"

        /** Declared device kind, see [DeviceInfo.deviceType]. Own section so an empty value shifts nothing. */
        const val CHARACTERISTICS_MARKER = "@@TVSLIM_C"

        /**
         * A phone's menu apps, disabled ones included so they stay listed and can be re-enabled. See
         * `Catalog.withMenuApps`.
         */
        const val MENU_MARKER = "@@TVSLIM_A"

        /** `com.google.android.youtube/com.google.android.apps.youtube.app.WatchWhileActivity` -> the package. */
        internal fun applicationsMenu(lines: List<String>): Set<String> = lines
            .filter { '/' in it && !it.startsWith("priority") }
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() && ' ' !in it }
            .toSet()
        const val FEATURES_MARKER = "@@TVSLIM_F"

        /** `feature:android.software.leanback`, `feature:android.hardware.touchscreen=1` -> the bare name. */
        internal fun features(lines: List<String>): Set<String> = lines
            .map { it.removePrefix("feature:").substringBefore('=').trim() }
            .filter { it in DeviceInfo.QUERIED_FEATURES }
            .toSet()

        const val HOME_COMMAND =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

        /** `df` after `diskstats`: some devices omit the `Data-Free` line. */
        const val STORAGE_COMMAND = "dumpsys diskstats; echo ${StorageReading.DF_MARKER}; df -k /data"

        // `--user 0` everywhere: without it, `pm list packages -e` reports a package as enabled if it is
        // enabled in any profile. The TCL has a second, never-opened profile (`new_user`, id 10) where
        // packages disabled for the main profile stay enabled. `pm disable-user` already targets user 0.
        val COMMAND = listOf(
            "echo $DISABLED_MARKER",
            "pm list packages -d --user 0",
            "echo $ACTIVE_MARKER",
            "pm list packages -e --user 0",
            "echo $PROPERTIES_MARKER",
            "getprop ro.product.manufacturer",
            "getprop ro.product.model",
            "getprop ro.build.version.release",
            "getprop ro.build.display.id",
            "echo $BRAND_MARKER",
            "getprop ro.product.brand",
            "echo $MEMORY_MARKER",
            "grep -E 'MemTotal|MemAvailable' /proc/meminfo",
            "echo $HOME_MARKER",
            HOME_COMMAND,
            "echo $LAUNCHERS_MARKER",
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME",
            "echo $ALL_HOMES_MARKER",
            "cmd package query-activities --brief --query-flags $MATCH_DISABLED_COMPONENTS " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME",
            "echo $CHARACTERISTICS_MARKER",
            "getprop ro.build.characteristics",
            "echo $FEATURES_MARKER",
            "pm list features",
            "echo $MENU_MARKER",
            "cmd package query-activities --brief --query-flags $MATCH_DISABLED_COMPONENTS " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
            // Last, so its exit code is the exit code of the whole command.
            "echo $THIRD_PARTY_MARKER",
            "pm list packages -3 --user 0",
        ).joinToString("; ")
    }
}
