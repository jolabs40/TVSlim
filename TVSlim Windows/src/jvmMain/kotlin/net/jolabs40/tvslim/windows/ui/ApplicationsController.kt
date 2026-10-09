package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.applications.ApplicationsActions
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.applications.ApplicationsCache
import net.jolabs40.tvslim.applications.ReadCause
import net.jolabs40.tvslim.applications.ApplicationsReader
import net.jolabs40.tvslim.applications.ReadResult
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.support.SupportInvitation
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.apps_disabled_done
import net.jolabs40.tvslim.windows.resources.apps_enabled_done
import net.jolabs40.tvslim.windows.resources.apps_failed
import net.jolabs40.tvslim.windows.resources.apps_opened
import net.jolabs40.tvslim.windows.resources.apps_read_connection
import net.jolabs40.tvslim.windows.resources.apps_read_helper_missing
import net.jolabs40.tvslim.windows.resources.apps_read_helper_refused
import net.jolabs40.tvslim.windows.resources.apps_read_send
import net.jolabs40.tvslim.windows.resources.apps_stopped
import net.jolabs40.tvslim.windows.resources.apps_uninstalled
import net.jolabs40.tvslim.windows.resources.msg_connect_first

/** An app action waiting for confirmation. */
sealed interface ApplyConfirmation {
    val application: DeviceApplication

    /** Goes through the engine and its safeguards; [entry] is the catalogue entry, side effects included. */
    data class Disabling(override val application: DeviceApplication, val entry: PackageEntry) : ApplyConfirmation

    data class Uninstallation(override val application: DeviceApplication) : ApplyConfirmation
}

data class ApplicationsState(
    val applications: List<DeviceApplication> = emptyList(),
    /** Set after a successful read, so the tab does not start another on its own. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Names and icons read so far, out of the total; `null` outside that step. */
    val progress: Pair<Int, Int>? = null,
    val search: String = "",
    val confirmation: ApplyConfirmation? = null,
    /** Package with an action in progress; its buttons are disabled. */
    val busy: String? = null,
) {
    val shown: List<DeviceApplication> by lazy {
        if (search.isBlank()) {
            applications
        } else {
            applications.filter { it.name.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
        }
    }
    val disabledCount: Int by lazy { applications.count { !it.active } }
}

/**
 * Applications tab: launcher apps and user-installed apps, with name and icon (`ApplicationsReader`, in the
 * core), and the actions on them.
 *
 * Disabling goes through the engine and its safeguards, with the Packages tab rules: on a TV only what the
 * catalogue describes, on a phone its launcher apps too. Re-enabling is always allowed. Uninstalling is limited to
 * user-installed apps and cannot be undone.
 */
class ApplicationsController(
    private val client: AdbClient,
    private val cache: ApplicationsCache,
    private val scope: CoroutineScope,
    private val show: (UiMessage) -> Unit,
    private val appState: () -> AppState,
    private val engine: () -> DebloatEngine?,
    journal: () -> JournalRepository?,
    /** Re-reads the Packages tab after a disable done from here. */
    private val refreshPackages: () -> Unit,
    private val thank: () -> Unit,
) {

    private val _state = MutableStateFlow(ApplicationsState())
    val state: StateFlow<ApplicationsState> = _state.asStateFlow()

    private val actions = ApplicationsActions(client, journal)

    init {
        // What was read belongs to the device: forget it on disconnect or when switching devices.
        scope.launch {
            client.connection
                .map { if (it.state == ConnectionState.DISCONNECTED) "" else it.host }
                .distinctUntilChanged()
                .drop(1)
                .collect { _state.value = ApplicationsState() }
        }
    }

    fun load() {
        if (!appState().connected) return show(text(Res.string.msg_connect_first))
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, progress = null) }
        scope.launch { read(client, report = true) }
    }

    /**
     * Same read, prefetched on connection over the second session. Skipped if the tab has read or is reading.
     * Failures stay silent: nobody asked, and the tab will read again on its own.
     */
    suspend fun preload(second: AdbClient) {
        if (_state.value.loaded || _state.value.loading) return
        _state.update { it.copy(loading = true, progress = null) }
        read(second, report = false)
    }

    private suspend fun read(session: AdbClient, report: Boolean) {
        try {
            val reader = ApplicationsReader(session, session, ::helper, cache)
            val result = reader.read { applications, done, total ->
                _state.update { it.copy(applications = applications, progress = if (done < total) done to total else null) }
            }
            when (result) {
                is ReadResult.Read -> _state.update { it.copy(applications = result.applications, loaded = true) }
                is ReadResult.Failure -> if (report) show(readFailureMessage(result))
            }
        } finally {
            // Also when a disconnect interrupts it: the tab must not wait for a read that will never finish.
            _state.update { it.copy(loading = false, progress = null) }
        }
    }

    private fun helper() = javaClass.classLoader.getResourceAsStream(ApplicationsReader.RESOURCE_PATH)

    private fun readFailureMessage(failure: ReadResult.Failure): UiMessage = when (failure.cause) {
        ReadCause.HELPER_MISSING -> text(Res.string.apps_read_helper_missing)
        ReadCause.UPLOAD -> text(Res.string.apps_read_send, UiMessage.Raw(failure.detail))
        ReadCause.HELPER_REJECTED -> text(Res.string.apps_read_helper_refused, UiMessage.Raw(failure.detail))
        ReadCause.CONNECTION -> text(Res.string.apps_read_connection, UiMessage.Raw(failure.detail))
    }

    fun updateSearch(rawValue: String) = _state.update { it.copy(search = rawValue) }

    /** Catalogue entry that allows disabling the app, if there is one and it is not protected. */
    fun disableableEntry(application: DeviceApplication): PackageEntry? {
        val catalog = appState().catalog
        if (catalog.isProtected(application.packageName)) return null
        return catalog.entries.firstOrNull { it.packageName == application.packageName }
    }

    fun open(application: DeviceApplication) = act(application) {
        val result = actions.open(application)
        show(if (result.succeeded) text(Res.string.apps_opened, application.name) else failure(result))
        false
    }

    fun forceStop(application: DeviceApplication) = act(application) {
        val result = engine()?.forceStop(application.packageName) ?: return@act false
        show(if (result.succeeded) text(Res.string.apps_stopped, application.name) else failure(result))
        false
    }

    fun requestDisable(application: DeviceApplication) {
        val entry = disableableEntry(application) ?: return
        _state.update { it.copy(confirmation = ApplyConfirmation.Disabling(application, entry)) }
    }

    fun enable(application: DeviceApplication) = act(application) {
        val result = engine()?.enable(listOf(application.packageName))?.singleOrNull() ?: return@act false
        show(if (result.succeeded) text(Res.string.apps_enabled_done, application.name) else failure(result))
        result.succeeded
    }

    fun requestUninstall(application: DeviceApplication) {
        if (application.system) return
        _state.update { it.copy(confirmation = ApplyConfirmation.Uninstallation(application)) }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val request = _state.value.confirmation ?: return
        cancelConfirmation()
        when (request) {
            is ApplyConfirmation.Disabling -> act(request.application) {
                val current = appState()
                val activeEngine = engine() ?: return@act false
                val results = activeEngine.disable(
                    entries = listOf(request.entry),
                    catalog = current.catalog,
                    states = current.lines.associate { it.entry.packageName to it.state },
                    launchersAvailable = current.info.thirdPartyLaunchers.isNotEmpty(),
                )
                val result = results.single()
                show(if (result.succeeded) text(Res.string.apps_disabled_done, request.application.name) else failure(result))
                if (SupportInvitation.deserves(results)) thank()
                result.succeeded
            }

            is ApplyConfirmation.Uninstallation -> act(request.application) {
                val result = actions.uninstall(request.application)
                show(if (result.succeeded) text(Res.string.apps_uninstalled, request.application.name) else failure(result))
                result.succeeded
            }
        }
    }

    /** One action at a time. When [block] returns true, the list and the Packages tab are re-read. */
    private fun act(application: DeviceApplication, block: suspend () -> Boolean) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = application.packageName) }
        scope.launch {
            val change = runCatching { block() }.getOrDefault(false)
            _state.update { it.copy(busy = null) }
            if (change) {
                refreshPackages()
                load()
            }
        }
    }

    private fun failure(result: ActionResult): UiMessage = text(Res.string.apps_failed, result.text())
}
