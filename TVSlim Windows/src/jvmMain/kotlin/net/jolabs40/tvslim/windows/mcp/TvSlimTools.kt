package net.jolabs40.tvslim.windows.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.withMenuApps
import net.jolabs40.tvslim.configuration.driftPlan
import net.jolabs40.tvslim.device.DeviceSnapshot
import net.jolabs40.tvslim.device.PackageOrigin
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.device.UnknownsReport
import net.jolabs40.tvslim.device.UnknownsSurvey
import net.jolabs40.tvslim.device.unknownPackages
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.windows.AppInfo
import net.jolabs40.tvslim.windows.adb.ConnectionProblem
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.network.DiscoveryResult
import net.jolabs40.tvslim.windows.network.TvAddress
import net.jolabs40.tvslim.windows.network.fileKey
import net.jolabs40.tvslim.windows.network.readPairingCode
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The tools Claude gets. They read the TV, the catalogue and the journal, and change nothing: changes stay in the
 * app, behind its safeguards and its confirmations.
 *
 * Only a plain ADB session is opened. The second session and the helper APK the app pushes to list applications
 * are left out, so that reading leaves nothing on the TV.
 */
class TvSlimTools(
    private val link: TvLink,
    private val catalogs: CatalogRepository,
    private val preferences: WindowsPreferences,
    private val discoveries: () -> Flow<DiscoveryResult>,
    private val journals: File,
) {
    // One TV at a time: concurrent calls would otherwise interleave connections to different TVs.
    private val session = Mutex()

    val all: List<McpTool> = listOf(
        McpTool(
            name = "find_tvs",
            title = "Find TVs",
            description = "Lists the TVs TV Slim can reach: the one last joined from this PC and those found on the " +
                "local network (mDNS and a scan of port 5555), with the names TV Slim knows them by. Takes a few seconds.",
            inputSchema = stringArguments(),
        ) { findTvs() },
        McpTool(
            name = "get_tv_overview",
            title = "TV overview",
            description = "Reads a TV: brand, model, Android version, memory, home screen, how many catalogue " +
                "packages are on it and how many are disabled, by category, how many packages the catalogue does " +
                "not know, and whether a system update turned packages back on. Start here.",
            inputSchema = stringArguments(ADDRESS),
        ) { overview(address(it)) },
        McpTool(
            name = "list_tv_packages",
            title = "Catalogue packages on a TV",
            description = "Lists the packages of TV Slim's catalogue present on a TV: state, category, risk, what " +
                "each does and what disabling it breaks, and the protected packages TV Slim never disables. " +
                "Optional filters: a category id or name, and a state (active or disabled).",
            inputSchema = stringArguments(
                ADDRESS,
                "category" to "A category id or name, such as pub_telemetrie or Advertising and telemetry.",
                "state" to "active or disabled.",
            ),
        ) { packages(address(it), it.string("category"), it.string("state")) },
        McpTool(
            name = "get_unknown_packages_report",
            title = "Unknown packages report",
            description = "Reads the system packages TV Slim's catalogue does not describe, with what can be told " +
                "without guessing: where each is installed, the identity it runs under, what it declares (TV input, " +
                "keyboard, accessibility, start at boot), memory and storage. Returns the same Markdown inventory, " +
                "in French, that TV Slim exports for a catalogue report on GitHub. Takes about ten seconds.",
            inputSchema = stringArguments(ADDRESS),
        ) { unknowns(address(it)) },
        McpTool(
            name = "get_journal",
            title = "TV Slim journal",
            description = "What TV Slim did to a TV from this PC, newest first: packages disabled and re-enabled, " +
                "home screen, settings, permissions, installs. Read from the journal on this PC; the TV can be off.",
            inputSchema = stringArguments(ADDRESS),
            reachesTv = false,
        ) { journal(address(it)) },
        McpTool(
            name = "check_drift",
            title = "Check for drift",
            description = "Compares a TV with what TV Slim left it in: packages it disabled that are active again, " +
                "and a home screen that fell back to the stock one, as a system update often does.",
            inputSchema = stringArguments(ADDRESS),
        ) { drift(address(it)) },
        McpTool(
            name = "describe_package",
            title = "Describe a package",
            description = "What TV Slim's catalogue says about one Android package name, without reading any TV: " +
                "description, risk, side effect, or why it is protected. Says so when the catalogue does not know it.",
            inputSchema = stringArguments("package" to "An Android package name, such as com.tcl.waterfall.overseas.", required = listOf("package")),
            reachesTv = false,
        ) { describe(it.string("package") ?: throw ToolFailure("Give a package name.")) },
    )

    private suspend fun findTvs(): String {
        val saved = preferences.read()
        val found = withTimeoutOrNull(DISCOVERY_MS) { discoveries().first { it.firstRoundDone } }
        return buildString {
            if (saved.lastHost.isNotBlank()) {
                val name = saved.knownNames[saved.lastHost]?.let { " ($it)" }.orEmpty()
                appendLine("Last TV joined from this PC: ${saved.lastHost}:${saved.lastPort}$name")
            }
            val devices = found?.devices.orEmpty().filterNot { it.wireless }
            if (devices.isEmpty()) {
                appendLine("No TV found on the local network. A TV is only found with network debugging on (Developer options).")
            } else {
                appendLine("Found on the local network:")
                devices.forEach { device ->
                    val name = device.friendlyName ?: saved.knownNames[device.host] ?: device.name
                    appendLine("- ${device.host}:${device.port}, $name")
                }
            }
            if (found == null) appendLine("The network scan did not finish in time; the list may be incomplete.")
        }.trimEnd()
    }

    private suspend fun overview(address: TvAddress): String = onTv(address) { reader ->
        val tv = read(reader)
        val info = tv.photo.info
        val unknowns = tv.catalog.unknownPackages(tv.photo.systemPackages, info.manufacturer)
        val drift = tv.drift(loadJournal(address))
        buildString {
            appendLine("${info.displayName} at ${address.host}:${address.port}: ${info.deviceType.name.lowercase()}, Android ${info.androidVersion}, build ${info.build}")
            appendLine("Memory: ${info.freeMemoryMb} MB free of ${info.totalMemoryMb} MB. Packages: ${info.installedPackages} installed, ${info.disabledPackages} disabled.")
            val home = tv.catalog.launcherName(info.currentHome) ?: info.currentHome.ifBlank { "unknown" }
            appendLine("Home screen: $home")
            // A release and its .debug build share a name.
            val others = info.thirdPartyLaunchers.map { tv.catalog.launcherName(it.packageName) ?: it.packageName }.distinct() - home
            if (others.isNotEmpty()) appendLine("Other home screens installed: ${others.joinToString()}")
            appendLine()
            val present = tv.present
            appendLine("Catalogue: ${present.size} of its packages are on this TV, ${present.count { it.second == PackageState.DISABLED }} disabled.")
            present.groupBy { it.first.category }.forEach { (category, lines) ->
                val disabled = lines.count { it.second == PackageState.DISABLED }
                appendLine("- ${tv.catalog.categoryName(category)} ($category): ${lines.size}, $disabled disabled")
            }
            appendLine("Protected packages on this TV, never disabled by TV Slim: ${tv.protectedPresent.size}")
            val byOrigin = unknowns.groupingBy { it.origin }.eachCount()
            appendLine(
                "Not in the catalogue: ${unknowns.size} (manufacturer ${byOrigin[PackageOrigin.MAKER] ?: 0}, " +
                    "Android ${byOrigin[PackageOrigin.ANDROID] ?: 0}, other ${byOrigin[PackageOrigin.OTHER] ?: 0}).",
            )
            appendLine()
            appendLine("Profiles offered in TV Slim:")
            tv.catalog.profiles.forEach { profile -> appendLine("- ${profile.name}: ${profile.description}") }
            appendLine()
            appendLine(
                if (drift == null) {
                    "Drift: none, the TV is as TV Slim left it."
                } else {
                    "Drift: ${drift.toDisable.size} package(s) TV Slim disabled are active again" +
                        (if (drift.home != null) ", and the home screen fell back to the stock one" else "") +
                        ". check_drift lists them."
                },
            )
        }.trimEnd()
    }

    private suspend fun packages(address: TvAddress, category: String?, state: String?): String = onTv(address) { reader ->
        val tv = read(reader)
        val wantedState = when (state?.lowercase()) {
            null, "" -> null
            "active" -> PackageState.ACTIVE
            "disabled" -> PackageState.DISABLED
            else -> throw ToolFailure("State must be active or disabled.")
        }
        val wantedCategory = category?.takeIf { it.isNotBlank() }?.let { asked ->
            tv.catalog.categories.firstOrNull { it.id.equals(asked, true) || it.name.equals(asked, true) }?.id
                ?: throw ToolFailure("No such category. Categories: ${tv.catalog.categories.joinToString { "${it.id} (${it.name})" }}")
        }
        val lines = tv.present.filter { (entry, entryState) ->
            (wantedCategory == null || entry.category == wantedCategory) && (wantedState == null || entryState == wantedState)
        }
        buildString {
            if (lines.isEmpty()) appendLine("No catalogue package matches on this TV.")
            lines.groupBy { it.first.category }.forEach { (id, group) ->
                appendLine("## ${tv.catalog.categoryName(id)} ($id)")
                group.forEach { (entry, entryState) -> appendLine(describeEntry(entry, tv.catalog, entryState)) }
                appendLine()
            }
            if (wantedCategory == null && tv.protectedPresent.isNotEmpty()) {
                appendLine("## Protected, never disabled by TV Slim")
                tv.protectedPresent.forEach { appendLine("- `${it.packageName}`: ${it.protectionReason}") }
            }
        }.trimEnd()
    }

    private suspend fun unknowns(address: TvAddress): String = onTv(address) { reader ->
        val tv = read(reader)
        val survey = UnknownsSurvey(
            hints = reader.hints(),
            memory = reader.memory(),
            storage = reader.storage(),
            firmware = reader.firmware(),
        )
        UnknownsReport.markdown(
            info = tv.photo.info,
            unknowns = tv.catalog.unknownPackages(tv.photo.systemPackages, tv.photo.info.manufacturer),
            application = "TV Slim pour Windows ${AppInfo.VERSION}",
            survey = survey,
            fromCatalog = tv.present.toMap(),
        )
    }

    private suspend fun journal(address: TvAddress): String {
        val actions = loadJournal(address)
        if (actions.isEmpty()) return "TV Slim has recorded nothing for ${address.host} on this PC."
        return buildString {
            appendLine("${actions.size} action(s) recorded for ${address.host}, newest first:")
            actions.sortedByDescending { it.timestamp }.take(JOURNAL_LINES).forEach { action ->
                append("- ${DATE.format(Instant.ofEpochMilli(action.timestamp))} ${action.type.label}: ${action.label}")
                if (action.label != action.target) append(" (`${action.target}`)")
                if (!action.succeeded) append(", failed${action.message.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
                appendLine()
            }
            if (actions.size > JOURNAL_LINES) appendLine("…and ${actions.size - JOURNAL_LINES} older.")
        }.trimEnd()
    }

    private suspend fun drift(address: TvAddress): String = onTv(address) { reader ->
        val tv = read(reader)
        val plan = tv.drift(loadJournal(address)) ?: return@onTv "No drift: every package TV Slim disabled is still disabled, and the home screen has not fallen back."
        buildString {
            if (plan.toDisable.isNotEmpty()) {
                appendLine("Disabled by TV Slim, active again:")
                plan.toDisable.forEach { appendLine(describeEntry(it, tv.catalog, PackageState.ACTIVE)) }
            }
            plan.home?.let { appendLine("Home screen fell back to the stock one; TV Slim had set ${it.name}.") }
            appendLine()
            append("TV Slim shows this on its TV tab and disables them again after a confirmation. A package re-enabled on purpose from the TV's settings is listed too.")
        }.trimEnd()
    }

    private suspend fun describe(packageName: String): String {
        val catalog = catalogs.catalog()
        catalog.entries.firstOrNull { it.packageName == packageName }?.let { return describeEntry(it, catalog) }
        catalog.protectionReason(packageName)?.let { return "`$packageName` is protected: TV Slim never disables it. $it" }
        catalog.launcherName(packageName)?.let { return "`$packageName` is a home screen TV Slim recognizes: $it." }
        return "`$packageName` is not in TV Slim's catalogue, which has no description of it. Do not infer what it " +
            "does from its name alone. If it came with the TV, get_unknown_packages_report gives what can be read."
    }

    private suspend fun <T> onTv(address: TvAddress, block: suspend (RemoteReader) -> T): T = session.withLock {
        if (!link.isConnectedTo(address)) {
            link.disconnect()
            link.connect(address)?.let { throw ToolFailure(connectionFailure(address, it)) }
        }
        block(RemoteReader(link.executor))
    }

    private suspend fun read(reader: RemoteReader): TvReading {
        val catalog = catalogs.catalog()
        val photo = reader.snapshot(
            watchedPackages = catalog.entries.map { it.packageName },
            homePackages = catalog.entries.filter { it.requiresThirdPartyLauncher }.map { it.packageName }.toSet(),
        )
        if (photo.systemPackages.isEmpty() && photo.states.isEmpty()) throw ToolFailure("The TV did not answer the read. Try again.")
        return TvReading(catalog.withMenuApps(photo.info, photo.systemPackages, photo.applicationsMenu), photo)
    }

    private suspend fun loadJournal(address: TvAddress): List<JournalAction> {
        val file = File(journals, "${fileKey(address.host)}.json")
        if (!file.isFile) return emptyList()
        val repository = JournalRepository(file)
        repository.load()
        return repository.actions.value
    }

    /** From the model, so filtered like pasted text: a local IPv4 address and a valid port. */
    private suspend fun address(arguments: JsonObject): TvAddress {
        val given = arguments.string("address")?.takeIf { it.isNotBlank() }
        if (given != null) {
            return readPairingCode(given)
                ?: throw ToolFailure("\"$given\" is not a local network address. Give an IPv4 address such as 192.168.1.20 or 192.168.1.20:5555.")
        }
        val saved = preferences.read()
        if (saved.lastHost.isBlank()) throw ToolFailure("No TV given, and TV Slim has not joined one from this PC yet. Call find_tvs first.")
        return TvAddress(saved.lastHost, saved.lastPort)
    }

    private fun connectionFailure(address: TvAddress, problem: ConnectionProblem): String {
        val where = "${address.host}:${address.port}"
        return when (problem) {
            ConnectionProblem.UNAUTHORIZED, ConnectionProblem.TIMEOUT ->
                "The TV at $where did not authorize this PC in time. An \"Allow USB debugging?\" prompt should be on its " +
                    "screen: the person accepts it with the remote, ticking \"Always allow from this computer\", then asks again."
            ConnectionProblem.UNREACHABLE ->
                "Nothing answers at $where: the TV may be off or asleep, on another network, or have network debugging turned off."
            ConnectionProblem.REJECTED ->
                "The TV at $where refused the connection. Network debugging may be off, or ADB may listen on another port. " +
                    "Wireless debugging with a pairing code (Android 11 and later) is not supported."
            ConnectionProblem.OTHER -> "Could not connect to $where."
        }
    }

    companion object {
        val INSTRUCTIONS = """
            TV Slim reads an Android TV or Google TV over ADB from this PC: what it runs, what TV Slim's catalogue says
            about each package, what TV Slim changed (its journal), and what a system update turned back on (drift).

            Every tool here only reads. Disabling, re-enabling and installing are done by the person in the TV Slim app,
            which applies its safeguards and keeps everything reversible. Do not offer raw `adb shell pm` commands
            instead: protected packages (tuner, inputs, remote, Play services) can leave a TV unusable.

            Packages outside the catalogue are described nowhere TV Slim knows. Say so rather than guessing from a
            package name. The unknown packages report is what the person can attach to a catalogue request at
            https://github.com/jolabs40/TVSlim/issues/new?template=nouvel-appareil.yml

            The first connection to a TV shows an authorization prompt on its screen, accepted with the remote.
        """.trimIndent()

        private val ADDRESS = "address" to
            "The TV's local IPv4 address, such as 192.168.1.20 or 192.168.1.20:5555. Leave it out for the TV last joined from this PC."

        private const val DISCOVERY_MS = 8_000L
        private const val JOURNAL_LINES = 100
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}

/** A TV as just read, with the catalogue fitted to it. */
private class TvReading(val catalog: Catalog, val photo: DeviceSnapshot) {
    fun state(packageName: String) =
        photo.states[packageName] ?: photo.systemPackages[packageName] ?: PackageState.ABSENT

    val present: List<Pair<PackageEntry, PackageState>> =
        catalog.entries.map { it to state(it.packageName) }.filter { it.second != PackageState.ABSENT }

    val protectedPresent = catalog.protectedPackages.filter { state(it.packageName) != PackageState.ABSENT }

    fun drift(journal: List<JournalAction>) =
        catalog.driftPlan(journal, present.associate { it.first.packageName to it.second }, photo.info)
}

private fun describeEntry(entry: PackageEntry, catalog: Catalog, state: PackageState? = null) = buildString {
    append("- `${entry.packageName}`, ${entry.name}")
    if (state != null) append(": ${state.name.lowercase()}")
    append(". Category ${catalog.categoryName(entry.category)}, risk ${entry.risk.name.lowercase()}.")
    if (!entry.tested) append(" Not yet tried on a real TV.")
    append(" ${entry.description}")
    entry.sideEffect?.let { append(" If disabled: $it") }
}

private val ActionType.label: String
    get() = when (this) {
        ActionType.DISABLING -> "disabled"
        ActionType.ENABLING -> "re-enabled"
        ActionType.SETTING -> "setting"
        ActionType.HOME -> "home screen"
        ActionType.PERMISSION -> "permission"
        ActionType.APP_OP -> "app-op"
        ActionType.INSTALLATION -> "installed"
        ActionType.COMMAND -> "ADB command"
        ActionType.UNINSTALLATION -> "uninstalled"
    }

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
