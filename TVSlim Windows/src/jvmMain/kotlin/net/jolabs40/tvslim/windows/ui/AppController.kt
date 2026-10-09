package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.windows.resources.msg_reboot_not_back
import net.jolabs40.tvslim.windows.resources.msg_reboot_back
import net.jolabs40.tvslim.device.Reboot
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.applications.FileCacheApplications
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.Profile
import net.jolabs40.tvslim.catalog.withMenuApps
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.RemoteReader
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
import net.jolabs40.tvslim.support.SupportInvitation
import net.jolabs40.tvslim.support.SupportController
import net.jolabs40.tvslim.windows.Locations
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.network.DiscoveredDevice
import net.jolabs40.tvslim.windows.network.TvDiscovery
import net.jolabs40.tvslim.windows.network.fileKey
import net.jolabs40.tvslim.windows.network.parseInput
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.msg_baseline_reset
import net.jolabs40.tvslim.windows.resources.msg_connect_first
import net.jolabs40.tvslim.windows.resources.msg_enter_address
import net.jolabs40.tvslim.windows.resources.msg_failure
import net.jolabs40.tvslim.windows.resources.msg_journal_export_failed
import net.jolabs40.tvslim.windows.resources.msg_journal_exported
import net.jolabs40.tvslim.windows.resources.msg_not_undoable
import net.jolabs40.tvslim.windows.resources.msg_nothing_selected
import net.jolabs40.tvslim.windows.resources.msg_nothing_to_restore
import net.jolabs40.tvslim.windows.resources.msg_stopped
import net.jolabs40.tvslim.windows.resources.msg_wireless_unsupported
import java.io.File

/**
 * Window controller: one ADB connection, one catalogue, one journal per TV.
 *
 * Mirrors the companion's `RemoteViewModel` decision for decision. The debloat engine comes from the
 * shared core and does not care where its privileges come from (here, an ADB session opened from the PC).
 */
class AppController(
    private val client: AdbClient,
    private val catalogRepo: CatalogRepository,
    private val preferences: WindowsPreferences,
    private val discovery: TvDiscovery,
    private val locations: Locations,
) : ViewModel() {

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val reader = RemoteReader(client)

    private var journal: JournalRepository? = null
    private var measurements: MeasurementsRepository? = null
    private var engine: DebloatEngine? = null
    private var apkInstallation: ApkInstallation? = null

    /** Privileged permissions have their own controller: their state is unrelated to debloating. */
    val permissions = PermissionsController(
        reader = reader,
        engine = { engine },
        scope = viewModelScope,
        show = ::show,
    )

    /** Support banner, shown after a successful debloat, transfer or install. */
    val support = SupportController(preferences, viewModelScope)

    /** Home screen card (launcher info, install watch) and saved configurations. Shares only state and engine. */
    val configuration = ConfigurationController(
        reader = reader,
        engine = { engine },
        installation = { apkInstallation },
        console = AdbConsole(client) { journal },
        state = { _state.value },
        updateState = { transformation -> _state.update { transformation(it) } },
        scope = viewModelScope,
        show = ::show,
        refresh = ::refresh,
        finish = ::finish,
        thank = support::thank,
    )

    /** Applications tab: names and icons read by the helper app, cached on disk. Tracks the connection itself. */
    val applications = ApplicationsController(
        client = client,
        cache = FileCacheApplications(locations.icons),
        scope = viewModelScope,
        show = ::show,
        appState = { _state.value },
        engine = { engine },
        journal = { journal },
        refreshPackages = ::refresh,
        thank = support::thank,
    )

    /** The TV Slim app on the TV, installed from GitHub. */
    val tvApp = TvAppController(
        client = client,
        reader = reader,
        engine = { engine },
        installation = { apkInstallation },
        folder = File(locations.local, "application-tv"),
        scope = viewModelScope,
        show = ::show,
        thank = support::thank,
    )

    /** Files tab. Tracks the connection itself and forgets what it read when the TV changes. */
    val files = FilesController(client, viewModelScope, ::show, support::thank)

    /** One journal subscription at a time, or the previous TV's journal would keep writing. */
    private var journalTracking: Job? = null

    /** One silent reconnection at a time. */
    private var retry: Job? = null

    /** Waits for a rebooted TV to come back. */
    private var rebooting: Job? = null

    /** Connection-time prefetch and the second session it runs on, one at a time. */
    private var preloadJob: Job? = null
    private var secondSession: AdbClient? = null

    /** Network discovery only runs while the connection screen is shown. */
    private var watchJob: Job? = null

    /** Set by "Disconnect", cleared by the next connection the user asks for. */
    private var voluntaryDisconnection = false

    init {
        viewModelScope.launch {
            client.connection.collect { connection -> _state.update { it.copy(connection = connection) } }
        }
        viewModelScope.launch {
            permissions.state.collect { fetched -> _state.update { it.copy(permissions = fetched) } }
        }
        viewModelScope.launch {
            // Read before `update`: its block reruns whenever one of the collectors above writes in
            // between, and disk reads have no place in a block that may run twice.
            val fetched = preferences.read()
            val catalog = catalogRepo.catalog()
            _state.update {
                it.copy(
                    enteredHost = it.enteredHost.ifBlank { fetched.lastHost },
                    enteredPort = if (it.enteredHost.isBlank()) fetched.lastPort.toString() else it.enteredPort,
                    knownNames = fetched.knownNames,
                    catalog = catalog,
                )
            }
        }
    }

    // --- Connection -----------------------------------------------------------------------

    fun searchDevices() {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            discovery.stream().collect { result -> _state.update { it.copy(discovery = result) } }
        }
    }

    fun stopSearch() {
        watchJob?.cancel()
        watchJob = null
    }

    /** Connects to a device found by discovery. */
    fun connectTo(device: DiscoveredDevice) {
        if (device.wireless) {
            show(text(Res.string.msg_wireless_unsupported))
            return
        }
        _state.update { it.copy(enteredHost = device.host, enteredPort = device.port.toString()) }
        connect()
    }

    fun updateHost(rawValue: String) = _state.update { it.copy(enteredHost = rawValue) }

    fun updatePort(rawValue: String) =
        _state.update { it.copy(enteredPort = rawValue.filter { c -> c.isDigit() }.take(5)) }

    fun connect() {
        val current = _state.value
        val address = parseInput(current.enteredHost, current.enteredPort)
        if (address == null) {
            show(text(Res.string.msg_enter_address))
            return
        }
        // "192.168.1.20:5555" pasted in one go is split into both fields.
        _state.update { it.copy(enteredHost = address.host, enteredPort = address.port.toString()) }
        voluntaryDisconnection = false
        retry?.cancel()
        viewModelScope.launch {
            if (client.connect(address.host, address.port)) {
                preferences.rememberAddress(address.host, address.port)
                openJournal(address.host)
                refresh()
                preload()
            }
        }
    }

    /**
     * Silently retries the last TV reached when the window is restored, since ADB sessions do not survive
     * TV standby. A failure (usually the TV is off) shows nothing: the user did not ask for anything.
     *
     * Never after "Disconnect": on a desktop the window regains focus on every click, so the session would
     * reopen behind the user's back. Never to an address that was only typed: only a past success is resumed.
     */
    fun resumeConnection() {
        // During a reboot, the reboot watcher reconnects; do not race it.
        if (voluntaryDisconnection || _state.value.connected || retry?.isActive == true || _state.value.rebooting) return
        if (_state.value.connection.state == ConnectionState.CONNECTION) return
        retry = viewModelScope.launch {
            val fetched = preferences.read()
            if (fetched.lastHost.isBlank()) return@launch
            if (client.connect(fetched.lastHost, fetched.lastPort, quiet = true)) {
                openJournal(fetched.lastHost)
                refresh()
                preload()
            }
        }
    }

    fun disconnect() {
        voluntaryDisconnection = true
        stopPreload()
        client.disconnect()
        listOf(retry, journalTracking).forEach { it?.cancel() }
        configuration.forget()
        retry = null
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
                // Otherwise this TV's memory reading would pass for the next TV's, and the tab would
                // not re-read it.
                memory = MemoryBreakdown(),
                memoryReadAttempted = false,
                storage = StorageBreakdown(),
                storageReadAttempted = false,
                detailedPackage = null,
            )
        }
    }

    fun refresh() {
        if (!_state.value.connected) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val catalog = catalogRepo.catalog()
            val homePackages = catalog.entries.filter { it.requiresThirdPartyLauncher }.map { it.packageName }.toSet()

            // A single command for everything: each round trip is costly over the network.
            val photo = reader.snapshot(
                watchedPackages = catalog.entries.map { it.packageName },
                homePackages = homePackages,
            )
            val selection = _state.value.selection.map { it.entry.packageName }.toSet()

            // Remember the model name to label the device next time.
            val name = photo.info.displayName
            val host = _state.value.connection.host
            if (name.isNotBlank()) preferences.rememberName(host, name)

            // Each snapshot doubles as a measurement; the first one is the baseline.
            measurements?.record(
                Measurement(
                    timestamp = System.currentTimeMillis(),
                    activePackages = photo.info.installedPackages,
                    disabledPackages = photo.info.disabledPackages,
                    totalMemoryMb = photo.info.totalMemoryMb,
                    freeMemoryMb = photo.info.freeMemoryMb,
                ),
            )

            // On a phone, the app drawer's apps join the catalogue: see avecApplicationsDuMenu.
            val deviceCatalog = catalog.withMenuApps(photo.info, photo.systemPackages, photo.applicationsMenu)
            _state.update { current ->
                current.copy(
                    loading = false,
                    catalog = deviceCatalog,
                    info = photo.info,
                    knownNames = if (name.isBlank()) current.knownNames else current.knownNames + (host to name),
                    lines = deviceCatalog.entries.map { entry ->
                        val packageState = photo.states[entry.packageName] ?: photo.systemPackages[entry.packageName] ?: PackageState.ABSENT
                        PackageRow(
                            entry = entry,
                            state = packageState,
                            selected = entry.packageName in selection && packageState == PackageState.ACTIVE,
                        )
                    },
                    // Packages missing from the catalogue: listed apart, no action offered.
                    unknowns = deviceCatalog.unknownPackages(photo.systemPackages, photo.info.manufacturer),
                )
            }
        }
    }

    // --- Package list ---------------------------------------------------------------------

    fun updateSearch(rawValue: String) = _state.update { it.copy(search = rawValue) }

    fun updateFilter(filter: PackageFilter) = _state.update { it.copy(filter = filter) }

    fun showDetails(packageName: String) = _state.update { it.copy(detailedPackage = packageName) }

    fun toggleSelection(packageName: String) = _state.update { it.withToggled(packageName) }

    fun selectProfile(profile: Profile) = _state.update { it.withProfile(profile) }

    fun deselectAll() = _state.update { it.withoutSelection() }

    // --- Confirmation ---------------------------------------------------------------------

    /** Asks for confirmation before disabling; nothing is sent until confirmed. */
    fun requestApply() {
        val current = _state.value
        val chosen = current.selection.map { it.entry }
        when {
            chosen.isEmpty() -> show(text(Res.string.msg_nothing_selected))
            !current.connected -> show(text(Res.string.msg_connect_first))
            else -> _state.update { it.copy(confirmation = Confirmation.Application(chosen)) }
        }
    }

    fun requestRestore() {
        val toRestore = journal?.activelyDisabledPackages().orEmpty()
        when {
            toRestore.isEmpty() -> show(text(Res.string.msg_nothing_to_restore))
            !_state.value.connected -> show(text(Res.string.msg_connect_first))
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
            finish(results)
            if (SupportInvitation.deserves(results)) support.thank()
        }
    }

    fun enable(packages: List<String>) {
        val activeEngine = engine
        if (packages.isEmpty() || activeEngine == null) {
            show(text(Res.string.msg_nothing_to_restore))
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(progress = BatchProgress(0, packages.size)) }
            val results = activeEngine.enable(packages) { done, total ->
                _state.update { it.copy(progress = BatchProgress(done, total)) }
            }
            finish(results)
        }
    }

    /** Undoes a single journal action. */
    fun undoAction(action: JournalAction) {
        when (action.type) {
            ActionType.DISABLING -> enable(listOf(action.target))
            ActionType.PERMISSION, ActionType.APP_OP -> permissions.undo(action)
            else -> show(text(Res.string.msg_not_undoable))
        }
    }

    /** Reads the memory breakdown. Kept out of [refresh] because the command is heavy. */
    fun refreshMemory() {
        if (!_state.value.connected) return
        viewModelScope.launch { readMemory(reader) }
    }

    /** Reads storage usage, kept separate like memory. */
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
            _state.update { it.copy(memory = memory, memoryReadAttempted = true) }
        } finally {
            _state.update { it.copy(memoryLoading = false) }
        }
    }

    private suspend fun readStorage(source: RemoteReader) {
        if (_state.value.storageLoading) return
        _state.update { it.copy(storageLoading = true) }
        try {
            val storage = source.storage()
            _state.update { it.copy(storage = storage, storageReadAttempted = true) }
        } finally {
            _state.update { it.copy(storageLoading = false) }
        }
    }

    /**
     * Reads apps, memory and storage right after connecting, over a second ADB session so the main one stays
     * free. A tab opened later finds its data ready or loading. Only what is missing is read, as on the phone.
     *
     * Apps first: fast once icons are cached, and also needed by the app picker in privileged permissions.
     */
    private fun preload() {
        val previous = preloadJob
        stopPreload()
        preloadJob = viewModelScope.launch {
            // Let the previous run reset its flags first; its session is closed, so it ends quickly.
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

    /** Closing the second session is what interrupts a running read; cancelling the job is not enough. */
    private fun stopPreload() {
        preloadJob?.cancel()
        preloadJob = null
        secondSession?.disconnect()
        secondSession = null
    }

    /** Kills an app's processes without changing its install state. */
    fun forceStop(packageName: String) {
        val activeEngine = engine ?: return
        viewModelScope.launch {
            val result = activeEngine.forceStop(packageName)
            show(
                if (result.succeeded) {
                    text(Res.string.msg_stopped, packageName)
                } else {
                    text(Res.string.msg_failure, result.text())
                },
            )
            refreshMemory()
        }
    }

    /** File name suggested in the save dialog. */
    fun journalExportName(): String =
        "TVSlim-${fileKey(_state.value.connection.host.ifBlank { "televiseur" })}.md"

    fun exportJournal(target: File) {
        val active = journal ?: return show(text(Res.string.msg_connect_first))
        viewModelScope.launch {
            val info = _state.value.info
            runCatching {
                active.exportMarkdown(
                    target = target,
                    header = "Appareil : ${info.brand} ${info.model} — Android " +
                        "${info.androidVersion} (${info.build})",
                )
            }
                .onSuccess { path -> show(text(Res.string.msg_journal_exported, path)) }
                .onFailure { show(text(Res.string.msg_journal_export_failed, it.message.orEmpty())) }
        }
    }

    /** Makes the current state the new "before" baseline. */
    fun resetReference() {
        val active = measurements ?: return
        viewModelScope.launch {
            active.resetReference()
            show(text(Res.string.msg_baseline_reset))
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun finish(results: List<ActionResult>) {
        val failures = results.filterNot { it.succeeded }
        show(
            UiMessage.Summary(
                successes = results.count { it.succeeded },
                total = results.size,
                failures = failures.take(MAX_FAILURES).map { it.name to it.text() },
            ),
        )
        _state.update { it.copy(progress = null) }
        deselectAll()
        refresh()
    }

    fun requestReboot() = _state.update { it.copy(confirmation = Confirmation.Reboot) }

    /**
     * Sends the reboot once (`Reboot`, in the core), closes the session, then waits for the TV and
     * reconnects silently. The drift card then shows whether the reboot undid anything. Same as on the phone.
     */
    private fun reboot() {
        val activeJournal = journal ?: return
        val host = _state.value.connection.host
        val port = _state.value.connection.port
        if (rebooting?.isActive == true) return
        rebooting = viewModelScope.launch {
            Reboot(client, activeJournal).reboot()
            disconnect()
            // Not a user "Disconnect": the normal resume may reopen the session later.
            voluntaryDisconnection = false
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
                show(text(Res.string.msg_reboot_back))
            } else {
                show(text(Res.string.msg_reboot_not_back))
            }
        }
    }

    private suspend fun openJournal(host: String) {
        journalTracking?.cancel()
        val key = fileKey(host)
        val opened = JournalRepository(File(locations.journals, "$key.json"))
        opened.load()
        journal = opened
        engine = DebloatEngine(client, opened)
        apkInstallation = ApkInstallation(client, client, opened)

        val recorded = MeasurementsRepository(File(locations.measurements, "$key.json"))
        recorded.load()
        measurements = recorded

        _state.update {
            it.copy(
                memory = MemoryBreakdown(),
                memoryReadAttempted = false,
                storage = StorageBreakdown(),
                storageReadAttempted = false,
                detailedPackage = null,
            )
        }
        journalTracking = viewModelScope.launch {
            launch { opened.actions.collect { actions -> _state.update { it.copy(journal = actions) } } }
            launch { recorded.history.collect { h -> _state.update { it.copy(measurements = h) } } }
        }
    }

    private fun show(message: UiMessage) = _state.update { it.copy(message = message) }

    /** The second session must not outlive the window. */
    override fun onCleared() = stopPreload()

    private companion object {
        const val MAX_FAILURES = 4

        /** A TV takes over twenty seconds to reopen ADB; no point knocking sooner. */
        const val REBOOT_WAIT_MS = 20_000L
        const val COMEBACK_TIMEOUT_MS = 180_000L
        const val COMEBACK_STEP_MS = 5_000L
    }
}
