package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.Profile
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.configuration.driftPlan
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.UnknownPackage
import net.jolabs40.tvslim.device.MemoryBreakdown
import net.jolabs40.tvslim.device.StorageBreakdown
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.measurement.MeasurementHistory
import net.jolabs40.tvslim.windows.adb.ConnectionUi
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.adb.DEFAULT_ADB_PORT
import net.jolabs40.tvslim.windows.network.DiscoveredDevice
import net.jolabs40.tvslim.windows.network.DiscoveryResult

/**
 * `PackageEntry` comes from the core, which is not built with the Compose compiler, so its stability is not
 * inferred. Without `@Immutable` every Packages row recomposes on each progress update.
 */
@Immutable
data class PackageRow(
    val entry: PackageEntry,
    val state: PackageState,
    val selected: Boolean = false,
)

enum class PackageFilter { ALL, ACTIVE, DISABLED }

/** An action waiting for the user's confirmation. */
sealed interface Confirmation {
    /** Disabling packages; the dialog stresses the side effects listed in the catalogue. */
    data class Application(val entries: List<PackageEntry>) : Confirmation

    data class Restore(val packages: List<String>) : Confirmation

    /**
     * Reapplying a saved configuration, showing only what it would change. With [drift], the plan comes
     * from the journal (`driftPlan`) instead of a file.
     */
    data class Reinjection(val plan: ReinjectionPlan, val drift: Boolean = false) : Confirmation

    data class Installation(val apk: ChosenApk) : Confirmation

    data object Reboot : Confirmation
}

@Immutable
data class BatchProgress(val done: Int, val total: Int)

/**
 * Window state: the companion's `RemoteState` plus the package detail pane and network discovery.
 *
 * Safe to mark immutable: every field is a `val` and lists are only replaced through `copy()`.
 */
@Immutable
data class AppState(
    val enteredHost: String = "",
    val enteredPort: String = DEFAULT_ADB_PORT.toString(),
    val connection: ConnectionUi = ConnectionUi(),
    /** The TV is rebooting; we wait for it to come back and reconnect. */
    val rebooting: Boolean = false,
    val loading: Boolean = false,
    val progress: BatchProgress? = null,
    val catalog: Catalog = Catalog(),
    val info: DeviceInfo = DeviceInfo.EMPTY,
    val lines: List<PackageRow> = emptyList(),
    /** Preinstalled packages missing from the catalogue: shown, never offered for removal. */
    val unknowns: List<UnknownPackage> = emptyList(),
    val journal: List<JournalAction> = emptyList(),
    val measurements: MeasurementHistory = MeasurementHistory(),
    val discovery: DiscoveryResult = DiscoveryResult(),
    val knownNames: Map<String, String> = emptyMap(),
    val search: String = "",
    val filter: PackageFilter = PackageFilter.ALL,
    val detailedPackage: String? = null,
    val memory: MemoryBreakdown = MemoryBreakdown(),
    /** Set once a memory read has succeeded or failed, so the "reading" state cannot last forever. */
    val memoryReadAttempted: Boolean = false,
    /** A memory read is running, started by the tab or prefetched on connection. */
    val memoryLoading: Boolean = false,
    val storage: StorageBreakdown = StorageBreakdown(),
    /** Same as for memory: a failed read is reported instead of showing "reading" forever. */
    val storageReadAttempted: Boolean = false,
    val storageLoading: Boolean = false,
    val permissions: PermissionsState = PermissionsState(),
    val installation: InstallationState = InstallationState(),
    val command: CommandState = CommandState(),
    val confirmation: Confirmation? = null,
    val message: UiMessage? = null,
) {
    val connected: Boolean get() = connection.state == ConnectionState.CONNECTED
    val workInProgress: Boolean get() = progress != null
    val selection: List<PackageRow> by lazy { lines.filter { it.selected } }

    // Lazy, computed once per state: the Packages screen reads several of these and each one scans
    // the whole catalogue.
    private val present: List<PackageRow> by lazy {
        lines.filter { it.state != PackageState.ABSENT }
    }

    /** Rows left once the search and filter are applied. */
    val shown: List<PackageRow> by lazy {
        present
            .filter { line ->
                when (filter) {
                    PackageFilter.ALL -> true
                    PackageFilter.ACTIVE -> line.state == PackageState.ACTIVE
                    PackageFilter.DISABLED -> line.state == PackageState.DISABLED
                }
            }
            .filter { line ->
                search.isBlank() ||
                    line.entry.name.contains(search, ignoreCase = true) ||
                    line.entry.packageName.contains(search, ignoreCase = true)
            }
    }

    /** Unknown packages under the same filter and search as catalogue rows. */
    val shownUnknowns: List<UnknownPackage> by lazy {
        unknowns
            .filter { unknown ->
                when (filter) {
                    PackageFilter.ALL -> true
                    PackageFilter.ACTIVE -> unknown.state == PackageState.ACTIVE
                    PackageFilter.DISABLED -> unknown.state == PackageState.DISABLED
                }
            }
            .filter { search.isBlank() || it.packageName.contains(search, ignoreCase = true) }
    }

    val activeCount: Int by lazy { present.count { it.state == PackageState.ACTIVE } }
    val disabledCount: Int by lazy { present.count { it.state == PackageState.DISABLED } }

    /** Package in the detail pane, as long as it is still listed. */
    val detailedRow: PackageRow? by lazy {
        detailedPackage?.let { packageName -> shown.firstOrNull { it.entry.packageName == packageName } }
    }

    /** Discovered devices, named by their cast name, or else by the name seen on a previous visit. */
    val detected: List<DiscoveredDevice> by lazy {
        discovery.devices.map { it.copy(friendlyName = it.friendlyName ?: knownNames[it.host]) }
    }

    /**
     * What TV Slim disabled and the TV re-enabled on its own, usually after a system update (see
     * `driftPlan`). Null while loading or applying, since a half-applied state would look like drift.
     */
    val drift: ReinjectionPlan? by lazy {
        if (!connected || loading || workInProgress || lines.isEmpty()) return@lazy null
        catalog.driftPlan(journal, lines.associate { it.entry.packageName to it.state }, info)
    }
}

// --- Selection ------------------------------------------------------------------------------
//
// Pure state-to-state functions, identical to the companion's and tested the same way.

/** Toggles a package. Disabled or absent packages cannot be selected. */
fun AppState.withToggled(packageName: String): AppState = copy(
    lines = lines.map { line ->
        if (line.entry.packageName == packageName && line.state == PackageState.ACTIVE) {
            line.copy(selected = !line.selected)
        } else {
            line
        }
    },
)

/**
 * Selects everything a profile covers, never unselecting anything. Untested entries are never covered:
 * they must be ticked by hand.
 */
fun AppState.withProfile(profile: Profile): AppState = if (!info.deviceType.forCatalog) this else copy(
    lines = lines.map { line ->
        val covered = line.entry.category in profile.categories && line.entry.tested
        if (covered && line.state == PackageState.ACTIVE) {
            line.copy(selected = true)
        } else {
            line
        }
    },
)

fun AppState.withoutSelection(): AppState =
    copy(lines = lines.map { it.copy(selected = false) })
