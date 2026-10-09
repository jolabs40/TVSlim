package net.jolabs40.tvslim.remote.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.Profile
import net.jolabs40.tvslim.catalog.withMenuApps
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.device.Reboot
import net.jolabs40.tvslim.device.MemoryBreakdown
import net.jolabs40.tvslim.device.StorageBreakdown
import net.jolabs40.tvslim.device.unknownPackages
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.measurement.MeasurementHistory
import net.jolabs40.tvslim.measurement.Measurement
import net.jolabs40.tvslim.measurement.MeasurementsRepository
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.DiscoveredDevice
import net.jolabs40.tvslim.remote.adb.AdbClient
import net.jolabs40.tvslim.remote.adb.TvDiscovery
import net.jolabs40.tvslim.remote.adb.DEFAULT_ADB_PORT
import net.jolabs40.tvslim.remote.data.RemotePreferences
import net.jolabs40.tvslim.support.SupportInvitation
import net.jolabs40.tvslim.support.SupportController
import java.io.File
import javax.inject.Inject

/**
 * Main view model: one ADB connection, one catalogue, one log per TV.
 *
 * The debloat engine comes from the shared core and is unaware of where its privileges come from (here, an ADB
 * session opened from the phone).
 */
@HiltViewModel
class RemoteViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: AdbClient,
    private val catalogRepo: CatalogRepository,
    private val preferences: RemotePreferences,
    private val discovery: TvDiscovery,
) : ViewModel() {

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private val reader = RemoteReader(client)

    private var journal: JournalRepository? = null
    private var measurements: MeasurementsRepository? = null
    private var engine: DebloatEngine? = null
    private var apkInstallation: ApkInstallation? = null

    val permissions = PermissionsController(
        context = context,
        reader = reader,
        engine = { engine },
        scope = viewModelScope,
        show = ::show,
    )

    /** Support banner, shown after a successful debloat, transfer or install. */
    val support = SupportController(preferences, viewModelScope)

    val configuration = ConfigurationController(
        context = context,
        reader = reader,
        engine = { engine },
        installation = { apkInstallation },
        console = AdbConsole(client) { journal },
        state = { _state.value },
        updateState = { transformation -> _state.update { transformation(it) } },
        scope = viewModelScope,
        show = ::show,
        refresh = ::refresh,
        finish = { results ->
            finish(results.count { it.succeeded }, results.size, results.filterNot { it.succeeded })
        },
        thank = support::thank,
    )

    /** Files tab; follows the connection itself and forgets what it read when the TV changes. */
    val files = FilesController(context, client, viewModelScope, ::show, support::thank)

    /** Apps tab; names and icons come from the helper and are cached. Follows the connection itself. */
    val applications = ApplicationsController(
        context = context,
        client = client,
        scope = viewModelScope,
        show = ::show,
        remoteState = { _state.value },
        engine = { engine },
        journal = { journal },
        refreshPackages = ::refresh,
        thank = support::thank,
    )

    /** The TV Slim app on the TV, installed from GitHub. */
    val tvApp = TvAppController(
        context = context,
        client = client,
        reader = reader,
        engine = { engine },
        installation = { apkInstallation },
        scope = viewModelScope,
        show = ::show,
        thank = support::thank,
    )

    /** TV screenshot, from the top bar. */
    val capture = CaptureController(context, client, viewModelScope, { _state.value.info }, { _state.value.connected }, ::show)

    /** One log observer at a time, otherwise the previous TV's log would keep writing into the state. */
    private var journalTracking: Job? = null

    /** One silent reconnect at a time, otherwise each return to the screen would start another. */
    private var retry: Job? = null

    /** Waits for a rebooted TV to come back. */
    private var rebooting: Job? = null

    /** Prefetch on connect and the second session carrying it; one at a time. */
    private var preloadJob: Job? = null
    private var secondSession: AdbClient? = null

    /** mDNS discovery, running only while the connection screen is shown. */
    private var watchJob: Job? = null

    init {
        viewModelScope.launch {
            client.connection.collect { connection -> _state.update { it.copy(connection = connection) } }
        }
        viewModelScope.launch {
            permissions.state.collect { fetched -> _state.update { it.copy(permissions = fetched) } }
        }
        viewModelScope.launch {
            // Read first, then update. `update` is a compare-and-set loop that replays its block on concurrent
            // writes, and the two collectors above write at the same time: disk reads inside it could be
            // repeated or applied to a stale snapshot.
            val host = preferences.lastHost()
            val port = preferences.lastPort()
            val names = preferences.knownNames()
            val catalog = catalogRepo.catalog()
            _state.update {
                it.copy(
                    enteredHost = host,
                    enteredPort = port.toString(),
                    knownNames = names,
                    catalog = catalog,
                )
            }
        }
    }

    /** Discovers TVs announcing `_adb._tcp` on the network (network debugging enabled). */
    fun searchDevices() {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            discovery.stream().collect { devices ->
                _state.update { current ->
                    current.copy(
                        // Cast name first, then the name remembered from a previous connection.
                        detected = devices.map { device ->
                            device.copy(
                                friendlyName = device.friendlyName
                                    ?: current.knownNames[device.host],
                            )
                        },
                    )
                }
            }
        }
    }

    fun stopSearch() {
        watchJob?.cancel()
        watchJob = null
    }

    fun connectTo(device: DiscoveredDevice) {
        _state.update { it.copy(enteredHost = device.host, enteredPort = device.port.toString()) }
        connect()
    }

    fun updateHost(rawValue: String) = _state.update { it.copy(enteredHost = rawValue.trim()) }

    fun updatePort(rawValue: String) =
        _state.update { it.copy(enteredPort = rawValue.filter { c -> c.isDigit() }) }

    fun updateSearch(rawValue: String) = _state.update { it.copy(search = rawValue) }

    fun updateFilter(filter: PackageFilter) = _state.update { it.copy(filter = filter) }

    fun connect() {
        val current = _state.value
        val host = current.enteredHost
        val port = current.enteredPort.toIntOrNull() ?: DEFAULT_ADB_PORT
        if (host.isBlank()) {
            show(context.getString(R.string.msg_enter_address))
            return
        }
        viewModelScope.launch {
            if (client.connect(host, port)) {
                preferences.rememberAddress(host, port)
                openJournal(host)
                refresh()
                preload()
            }
        }
    }

    /**
     * Silently reconnects to the last TV when the app comes back to the foreground.
     *
     * An ADB session does not survive TV standby. The key is authorized and the address known, so there is no
     * reason to show a disconnected app. Failure (usually a TV that is off) shows nothing.
     */
    fun resumeConnection() {
        // Connected but nothing read: phone standby cut the previous read. Read again.
        if (_state.value.connected) {
            if (_state.value.info.brand.isBlank() && _state.value.info.model.isBlank()) {
                refresh()
                preload()
            }
            return
        }
        // During a reboot, the reboot watcher reconnects; avoid racing it.
        if (retry?.isActive == true || _state.value.rebooting) return
        retry = viewModelScope.launch {
            val host = _state.value.enteredHost.ifBlank { preferences.lastHost() }
            if (host.isBlank()) return@launch
            val port = _state.value.enteredPort.toIntOrNull() ?: preferences.lastPort()
            if (client.connect(host, port, quiet = true)) {
                openJournal(host)
                refresh()
                preload()
            }
        }
    }

    /** Applies a scanned pairing code, then connects. */
    fun applyScan(rawValue: String) {
        val address = readPairingCode(rawValue)
        if (address == null) {
            show(context.getString(R.string.msg_code_unknown, rawValue.trim()))
            return
        }
        _state.update { it.copy(enteredHost = address.host, enteredPort = address.port.toString()) }
        connect()
    }

    fun reportScanFailure(reason: String) =
        show(reason.ifBlank { context.getString(R.string.msg_scan_cancelled) })

    fun disconnect() {
        stopPreload()
        client.disconnect()
        configuration.forget()
        retry?.cancel()
        retry = null
        journalTracking?.cancel()
        journalTracking = null
        journal = null
        measurements = null
        engine = null
        apkInstallation = null
        permissions.forget()
        tvApp.forget()
        _state.update {
            it.copy(
                lines = emptyList(),
                unknowns = emptyList(),
                info = DeviceInfo.EMPTY,
                journal = emptyList(),
                measurements = MeasurementHistory(),
                memory = MemoryBreakdown(),
                storage = StorageBreakdown(),
            )
        }
    }

    fun refresh() {
        if (!_state.value.connected) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val catalog = catalogRepo.catalog()
            val homePackages = catalog.entries
                .filter { it.requiresThirdPartyLauncher }
                .map { it.packageName }
                .toSet()

            // A single command for everything: each network round trip costs.
            val photo = reader.snapshot(
                watchedPackages = catalog.entries.map { it.packageName },
                homePackages = homePackages,
            )
            // A read cut short (app suspended by Android, session dropped) returns an empty snapshot; keep the
            // previous state instead.
            if (photo.info.brand.isBlank() && photo.info.model.isBlank()) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            val selection = _state.value.selection.map { it.entry.packageName }.toSet()

            // Remember the model to name the device next time.
            val name = photo.info.displayName
            val host = _state.value.connection.host
            if (name.isNotBlank()) preferences.rememberName(host, name)

            // Every snapshot is also a measurement; the first one is the baseline the debloated device is
            // compared with.
            measurements?.record(
                Measurement(
                    timestamp = System.currentTimeMillis(),
                    activePackages = photo.info.installedPackages,
                    disabledPackages = photo.info.disabledPackages,
                    totalMemoryMb = photo.info.totalMemoryMb,
                    freeMemoryMb = photo.info.freeMemoryMb,
                ),
            )

            // On a phone, launcher apps are added to the catalogue (see avecApplicationsDuMenu).
            val deviceCatalog = catalog.withMenuApps(photo.info, photo.systemPackages, photo.applicationsMenu)
            _state.update { current ->
                current.copy(
                    loading = false,
                    catalog = deviceCatalog,
                    info = photo.info,
                    knownNames = current.knownNames + (host to name),
                    lines = deviceCatalog.entries.map { entry ->
                        val packageState = photo.states[entry.packageName] ?: photo.systemPackages[entry.packageName] ?: PackageState.ABSENT
                        PackageRow(
                            entry = entry,
                            state = packageState,
                            selected = entry.packageName in selection &&
                                packageState == PackageState.ACTIVE,
                        )
                    },
                    // Packages missing from the catalogue: listed separately, no action offered.
                    unknowns = deviceCatalog.unknownPackages(photo.systemPackages, photo.info.manufacturer),
                )
            }
        }
    }

    fun toggleSelection(packageName: String) = _state.update { it.withToggled(packageName) }

    fun selectProfile(profile: Profile) = _state.update { it.withProfile(profile) }

    fun deselectAll() = _state.update { it.withoutSelection() }

    // --- Confirmation ---------------------------------------------------------------------

    /** Asks for confirmation before disabling; nothing is sent until confirmed. */
    fun requestApply() {
        val current = _state.value
        val chosen = current.selection.map { it.entry }
        when {
            chosen.isEmpty() -> show(context.getString(R.string.msg_nothing_selected))
            !current.connected -> show(context.getString(R.string.msg_connect_first))
            else -> _state.update { it.copy(confirmation = Confirmation.Application(chosen)) }
        }
    }

    fun requestRestore() {
        val toRestore = journal?.activelyDisabledPackages().orEmpty()
        when {
            toRestore.isEmpty() -> show(context.getString(R.string.msg_nothing_to_restore))
            !_state.value.connected -> show(context.getString(R.string.msg_connect_first))
            else -> _state.update { it.copy(confirmation = Confirmation.Restore(toRestore)) }
        }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        when (val request = _state.value.confirmation) {
            is Confirmation.Application -> applyEntries(request.entries)
            is Confirmation.Restore -> enable(request.packages)
            is Confirmation.Reinjection -> configuration.reinject(request.plan)
            is Confirmation.Installation -> configuration.installApk(request.apk)
            Confirmation.Reboot -> reboot()
            null -> Unit
        }
        cancelConfirmation()
    }

    // --- Actions --------------------------------------------------------------------------

    private fun applyEntries(entries: List<PackageEntry>) {
        val current = _state.value
        val activeEngine = engine ?: return
        viewModelScope.launch {
            _state.update { it.copy(progress = BatchProgress(0, entries.size)) }
            val results = activeEngine.disable(
                entries = entries,
                catalog = current.catalog,
                states = current.lines.associate { it.entry.packageName to it.state },
                launchersAvailable = current.info.thirdPartyLaunchers.isNotEmpty(),
                onProgress = { done, total ->
                    _state.update { it.copy(progress = BatchProgress(done, total)) }
                },
            )
            finish(results.count { it.succeeded }, results.size, results.filterNot { it.succeeded })
            if (SupportInvitation.deserves(results)) support.thank()
        }
    }

    fun enable(packages: List<String>) {
        val activeEngine = engine
        if (packages.isEmpty() || activeEngine == null) {
            show(context.getString(R.string.msg_nothing_to_restore))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(progress = BatchProgress(0, packages.size)) }
            val results = activeEngine.enable(packages) { done, total ->
                _state.update { it.copy(progress = BatchProgress(done, total)) }
            }
            finish(results.count { it.succeeded }, results.size, results.filterNot { it.succeeded })
        }
    }

    /** Undoes a single log entry. */
    fun undoAction(action: JournalAction) {
        when (action.type) {
            ActionType.DISABLING -> enable(listOf(action.target))
            ActionType.PERMISSION, ActionType.APP_OP -> permissions.undo(action)
            else -> show(context.getString(R.string.msg_not_undoable))
        }
    }

    /** Reads the memory breakdown. Kept out of [refresh] because the command is heavy. */
    fun refreshMemory() {
        if (!_state.value.connected) return
        viewModelScope.launch { readMemory(reader) }
    }

    fun refreshStorage() {
        if (!_state.value.connected) return
        viewModelScope.launch { readStorage(reader) }
    }

    /**
     * One memory read at a time, from the tab or the prefetch: `dumpsys meminfo` takes six seconds on the TCL,
     * so a tab opened during the prefetch waits for it instead of starting another.
     */
    private suspend fun readMemory(source: RemoteReader) {
        if (_state.value.memoryLoading) return
        _state.update { it.copy(memoryLoading = true) }
        try {
            val memory = source.memory()
            _state.update { it.copy(memory = memory) }
        } finally {
            _state.update { it.copy(memoryLoading = false) }
        }
    }

    private suspend fun readStorage(source: RemoteReader) {
        if (_state.value.storageLoading) return
        _state.update { it.copy(storageLoading = true) }
        try {
            val storage = source.storage()
            _state.update { it.copy(storage = storage) }
        } finally {
            _state.update { it.copy(storageLoading = false) }
        }
    }

    /**
     * Prefetches apps, memory and storage on connect through a second ADB session, leaving the main one free for
     * user actions; tabs then find their data read or being read. Only missing data is read; failures are silent
     * and the tab reads again on demand.
     *
     * Apps come first: fast once icons are cached, and also needed by the permissions card's app picker.
     */
    private fun preload() {
        val previous = preloadJob
        stopPreload()
        preloadJob = viewModelScope.launch {
            // Let the previous prefetch reset its flags first; its session is closed, so it ends quickly.
            previous?.join()
            val second = client.openSecond() ?: return@launch
            secondSession = second
            try {
                applications.preload(second)
                val secondReader = RemoteReader(second)
                if (!_state.value.memory.populated) readMemory(secondReader)
                if (!_state.value.storage.populated) readStorage(secondReader)
            } finally {
                second.disconnect()
                if (secondSession === second) secondSession = null
            }
        }
    }

    /** Closing the second session is what interrupts the read in progress; cancelling the job is not enough. */
    private fun stopPreload() {
        preloadJob?.cancel()
        preloadJob = null
        secondSession?.disconnect()
        secondSession = null
    }

    /** Stops an app's processes without changing its enabled state. */
    fun forceStop(packageName: String) {
        val activeEngine = engine ?: return
        viewModelScope.launch {
            val result = activeEngine.forceStop(packageName)
            show(
                if (result.succeeded) {
                    context.getString(R.string.msg_stopped, packageName)
                } else {
                    context.getString(R.string.msg_failure, result.text(context))
                },
            )
            refreshMemory()
        }
    }

    fun exportJournal() {
        val active = journal ?: return
        viewModelScope.launch {
            val info = _state.value.info
            val folder = context.getExternalFilesDir(null) ?: context.filesDir
            val name = "TVSlim-${fileKey(_state.value.connection.host)}.md"
            val path = active.exportMarkdown(
                target = File(folder, name),
                header = "Appareil : ${info.brand} ${info.model} — Android " +
                    "${info.androidVersion} (${info.build})",
            )
            show(context.getString(R.string.msg_journal_exported, path))
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun finish(
        successes: Int,
        total: Int,
        failures: List<ActionResult>,
    ) {
        show(
            buildString {
                append(context.getString(R.string.result_summary, successes, total))
                failures.take(MAX_FAILURES).forEach { append("\n${it.name} : ${it.text(context)}") }
            },
        )
        _state.update { it.copy(progress = null) }
        deselectAll()
        refresh()
    }

    /** Makes the current state the new baseline, e.g. after a factory reset or before a new pass. */
    fun resetReference() {
        val active = measurements ?: return
        viewModelScope.launch {
            active.resetReference()
            show(context.getString(R.string.msg_baseline_reset))
        }
    }

    fun requestReboot() = _state.update { it.copy(confirmation = Confirmation.Reboot) }

    /**
     * Sends the reboot once (the core's `Reboot`), closes the session, then waits for the TV to come back and
     * reconnects silently. The drift card then shows whether the reboot undid anything.
     */
    private fun reboot() {
        val activeJournal = journal ?: return
        val host = _state.value.connection.host
        val port = _state.value.connection.port
        if (rebooting?.isActive == true) return
        rebooting = viewModelScope.launch {
            Reboot(client, activeJournal).reboot()
            disconnect()
            _state.update { it.copy(rebooting = true) }
            delay(REBOOT_WAIT_MS)
            val returned = withTimeoutOrNull(COMEBACK_TIMEOUT_MS) {
                while (!client.connect(host, port, quiet = true)) delay(COMEBACK_STEP_MS)
                true
            } ?: false
            _state.update { it.copy(rebooting = false) }
            if (returned) {
                openJournal(host)
                refresh()
                preload()
                show(context.getString(R.string.msg_reboot_back))
            } else {
                show(context.getString(R.string.msg_reboot_not_back))
            }
        }
    }

    private suspend fun openJournal(host: String) {
        journalTracking?.cancel()
        val key = fileKey(host)
        val opened = JournalRepository(File(File(context.filesDir, "journaux"), "$key.json"))
        opened.load()
        journal = opened
        engine = DebloatEngine(client, opened)
        apkInstallation = ApkInstallation(client, client, opened)

        val recorded = MeasurementsRepository(File(File(context.filesDir, "mesures"), "$key.json"))
        recorded.load()
        measurements = recorded

        // Memory and storage read earlier belong to another session, possibly another TV.
        _state.update { it.copy(memory = MemoryBreakdown(), storage = StorageBreakdown()) }
        journalTracking = viewModelScope.launch {
            launch {
                opened.actions.collect { actions -> _state.update { it.copy(journal = actions) } }
            }
            launch {
                recorded.history.collect { h -> _state.update { it.copy(measurements = h) } }
            }
        }
    }

    private fun show(text: String) = _state.update { it.copy(message = text) }

    /** Closes the second session; the main one is a shared singleton and stays open. */
    override fun onCleared() = stopPreload()

    private companion object {
        const val MAX_FAILURES = 4

        /** A TV takes over twenty seconds to reopen ADB; no point trying earlier. */
        const val REBOOT_WAIT_MS = 20_000L
        const val COMEBACK_TIMEOUT_MS = 180_000L
        const val COMEBACK_STEP_MS = 5_000L
    }
}
