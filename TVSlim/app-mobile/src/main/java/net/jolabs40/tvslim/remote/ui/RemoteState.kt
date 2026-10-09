package net.jolabs40.tvslim.remote.ui

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
import net.jolabs40.tvslim.remote.adb.DiscoveredDevice
import net.jolabs40.tvslim.remote.adb.ConnectionUi
import net.jolabs40.tvslim.remote.adb.ConnectionState
import net.jolabs40.tvslim.remote.adb.DEFAULT_ADB_PORT

/**
 * Marked stable by hand: `PackageEntry` comes from the core, which is not built with the Compose compiler, so
 * stability inference does not cross the module boundary. Without this, every row of the Packages screen
 * recomposes on each progress update.
 */
@Immutable
data class PackageRow(
    val entry: PackageEntry,
    val state: PackageState,
    val selected: Boolean = false,
)

enum class PackageFilter { ALL, ACTIVE, DISABLED }

/** A pending action awaiting user confirmation. */
sealed interface Confirmation {
    /** Disabling packages; the dialog lists their known side effects. */
    data class Application(val entries: List<PackageEntry>) : Confirmation

    data class Restore(val packages: List<String>) : Confirmation

    /**
     * Re-applying a saved configuration; only what it will change is shown.
     * With [drift], the plan comes from the log rather than a file (see `driftPlan`).
     */
    data class Reinjection(val plan: ReinjectionPlan, val drift: Boolean = false) : Confirmation

    /** Installing an APK: the incoming app, its version, and what it replaces. */
    data class Installation(val apk: ChosenApk) : Confirmation

    /** Rebooting the TV: what it interrupts and what may not come back. */
    data object Reboot : Confirmation
}

@Immutable
data class BatchProgress(val done: Int, val total: Int)

/**
 * `@Immutable` holds: every field is a `val` and no list is ever mutated in place (changes go through `copy()`).
 * Lets screens skip recomposition when their part has not changed.
 */
@Immutable
data class RemoteState(
    val enteredHost: String = "",
    val enteredPort: String = DEFAULT_ADB_PORT.toString(),
    val connection: ConnectionUi = ConnectionUi(),
    /** The TV is rebooting; waiting for it to come back to reconnect. */
    val rebooting: Boolean = false,
    val loading: Boolean = false,
    val progress: BatchProgress? = null,
    val catalog: Catalog = Catalog(),
    val info: DeviceInfo = DeviceInfo.EMPTY,
    val lines: List<PackageRow> = emptyList(),
    /** Preinstalled packages missing from the catalogue: shown, never offered for disabling. */
    val unknowns: List<UnknownPackage> = emptyList(),
    val journal: List<JournalAction> = emptyList(),
    val measurements: MeasurementHistory = MeasurementHistory(),
    val detected: List<DiscoveredDevice> = emptyList(),
    val knownNames: Map<String, String> = emptyMap(),
    val search: String = "",
    val filter: PackageFilter = PackageFilter.ALL,
    val memory: MemoryBreakdown = MemoryBreakdown(),
    /** A memory read is running, started by the tab or prefetched on connect. */
    val memoryLoading: Boolean = false,
    val storage: StorageBreakdown = StorageBreakdown(),
    val storageLoading: Boolean = false,
    val permissions: PermissionsState = PermissionsState(),
    val installation: InstallationState = InstallationState(),
    val command: CommandState = CommandState(),
    val shizuku: ShizukuState = ShizukuState(),
    val confirmation: Confirmation? = null,
    val message: String? = null,
) {
    val connected: Boolean get() = connection.state == ConnectionState.CONNECTED
    val workInProgress: Boolean get() = progress != null
    val selection: List<PackageRow> by lazy { lines.filter { it.selected } }

    // Lazy, computed once per state: the Packages screen reads several of these derived lists, and each one
    // would otherwise walk the whole catalogue again.
    private val present: List<PackageRow> by lazy {
        lines.filter { it.state != PackageState.ABSENT }
    }

    /** Rows shown after search and filter. */
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

    /** Unknown packages shown, with the same filter and search as the catalogue. */
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

    /**
     * Packages TV Slim disabled that the TV re-enabled on its own, usually after a system update
     * (see `driftPlan`). Null while reading or working: a half-applied state would look like drift.
     */
    val drift: ReinjectionPlan? by lazy {
        if (!connected || loading || workInProgress || lines.isEmpty()) return@lazy null
        catalog.driftPlan(journal, lines.associate { it.entry.packageName to it.state }, info)
    }
}

// --- Selection transforms ----------------------------------------------------------------
//
// Pure state-to-state functions, kept out of the view model so they are easy to test.

/** Toggles a package. Disabled or missing packages cannot be selected. */
fun RemoteState.withToggled(packageName: String): RemoteState = copy(
    lines = lines.map { line ->
        if (line.entry.packageName == packageName && line.state == PackageState.ACTIVE) {
            line.copy(selected = !line.selected)
        } else {
            line
        }
    },
)

/**
 * Selects everything a profile covers, never deselecting anything. Untested entries are never covered by a
 * profile; they must be selected by hand.
 */
fun RemoteState.withProfile(profile: Profile): RemoteState = if (!info.deviceType.forCatalog) this else copy(
    lines = lines.map { line ->
        val covered = line.entry.category in profile.categories && line.entry.tested
        if (covered && line.state == PackageState.ACTIVE) {
            line.copy(selected = true)
        } else {
            line
        }
    },
)

fun RemoteState.withoutSelection(): RemoteState =
    copy(lines = lines.map { it.copy(selected = false) })
