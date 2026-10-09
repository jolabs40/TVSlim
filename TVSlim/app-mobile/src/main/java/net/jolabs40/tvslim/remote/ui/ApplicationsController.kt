package net.jolabs40.tvslim.remote.ui

import android.content.Context
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
import net.jolabs40.tvslim.applications.FileCacheApplications
import net.jolabs40.tvslim.applications.ReadCause
import net.jolabs40.tvslim.applications.ApplicationsReader
import net.jolabs40.tvslim.applications.ReadResult
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.AdbClient
import net.jolabs40.tvslim.remote.adb.ConnectionState
import net.jolabs40.tvslim.support.SupportInvitation
import java.io.File

/** An app action awaiting confirmation. */
sealed interface ApplyConfirmation {
    val application: DeviceApplication

    /** Goes through the engine and its safeguards; [entry] is the catalogue entry, side effects included. */
    data class Disabling(override val application: DeviceApplication, val entry: PackageEntry) : ApplyConfirmation

    data class Uninstallation(override val application: DeviceApplication) : ApplyConfirmation
}

data class ApplicationsState(
    val applications: List<DeviceApplication> = emptyList(),
    /** A read succeeded; the tab does not start another one by itself. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Names and icons read so far, out of the total; null outside that step. */
    val progress: Pair<Int, Int>? = null,
    val search: String = "",
    /** App whose action sheet is open. */
    val chosen: DeviceApplication? = null,
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
 * Apps tab: launcher apps and user-installed apps with their names and icons (the core's `ApplicationsReader`),
 * and their actions. Same rules as the Windows app.
 */
class ApplicationsController(
    private val context: Context,
    private val client: AdbClient,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val remoteState: () -> RemoteState,
    private val engine: () -> DebloatEngine?,
    journal: () -> JournalRepository?,
    /** Reloads the Packages tab after a disable done here. */
    private val refreshPackages: () -> Unit,
    private val thank: () -> Unit,
) {

    private val _state = MutableStateFlow(ApplicationsState())
    val state: StateFlow<ApplicationsState> = _state.asStateFlow()

    private val cache = FileCacheApplications(File(context.cacheDir, "icones"))
    private val actions = ApplicationsActions(client, journal)

    init {
        // What was read belongs to that device: forget it on disconnect or when connecting to another one.
        scope.launch {
            client.connection
                .map { if (it.state == ConnectionState.DISCONNECTED) "" else it.host }
                .distinctUntilChanged()
                .drop(1)
                .collect { _state.value = ApplicationsState() }
        }
    }

    fun load() {
        if (!remoteState().connected) return show(context.getString(R.string.msg_connect_first))
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, progress = null) }
        scope.launch { read(client, report = true) }
    }

    /**
     * Same read, prefetched on connect through the second session. Skipped if the tab has read or is reading;
     * failures are silent since the tab will read again on its own.
     */
    suspend fun preload(second: AdbClient) {
        if (_state.value.loaded || _state.value.loading) return
        _state.update { it.copy(loading = true, progress = null) }
        read(second, report = false)
    }

    private suspend fun read(session: AdbClient, report: Boolean) {
        try {
            val reader = ApplicationsReader(session, session, { runCatching { context.assets.open(ApplicationsReader.RESOURCE_PATH) }.getOrNull() }, cache)
            val result = reader.read { applications, done, total ->
                _state.update { it.copy(applications = applications, progress = if (done < total) done to total else null) }
            }
            when (result) {
                is ReadResult.Read -> _state.update { it.copy(applications = result.applications, loaded = true) }
                is ReadResult.Failure -> if (report) show(readFailureMessage(result))
            }
        } finally {
            // Also on disconnect, so the tab does not wait for a read that will never finish.
            _state.update { it.copy(loading = false, progress = null) }
        }
    }

    private fun readFailureMessage(failure: ReadResult.Failure): String = when (failure.cause) {
        ReadCause.HELPER_MISSING -> context.getString(R.string.apps_read_helper_missing)
        ReadCause.UPLOAD -> context.getString(R.string.apps_read_send, failure.detail)
        ReadCause.HELPER_REJECTED -> context.getString(R.string.apps_read_helper_refused, failure.detail)
        ReadCause.CONNECTION -> context.getString(R.string.apps_read_connection, failure.detail)
    }

    fun updateSearch(rawValue: String) = _state.update { it.copy(search = rawValue) }

    fun choose(application: DeviceApplication?) = _state.update { it.copy(chosen = application) }

    /** Returns the catalogue entry that allows disabling the app, if it exists and is not protected. */
    fun disableableEntry(application: DeviceApplication): PackageEntry? {
        val catalog = remoteState().catalog
        if (catalog.isProtected(application.packageName)) return null
        return catalog.entries.firstOrNull { it.packageName == application.packageName }
    }

    fun open(application: DeviceApplication) = act(application) {
        val result = actions.open(application)
        show(if (result.succeeded) context.getString(R.string.apps_opened, application.name) else failure(result.text(context)))
        false
    }

    fun forceStop(application: DeviceApplication) = act(application) {
        val result = engine()?.forceStop(application.packageName) ?: return@act false
        show(if (result.succeeded) context.getString(R.string.apps_stopped, application.name) else failure(result.text(context)))
        false
    }

    fun requestDisable(application: DeviceApplication) {
        val entry = disableableEntry(application) ?: return
        _state.update { it.copy(chosen = null, confirmation = ApplyConfirmation.Disabling(application, entry)) }
    }

    fun enable(application: DeviceApplication) = act(application) {
        val result = engine()?.enable(listOf(application.packageName))?.singleOrNull() ?: return@act false
        show(if (result.succeeded) context.getString(R.string.apps_enabled_done, application.name) else failure(result.text(context)))
        result.succeeded
    }

    fun requestUninstall(application: DeviceApplication) {
        if (application.system) return
        _state.update { it.copy(chosen = null, confirmation = ApplyConfirmation.Uninstallation(application)) }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val request = _state.value.confirmation ?: return
        cancelConfirmation()
        when (request) {
            is ApplyConfirmation.Disabling -> act(request.application) {
                val current = remoteState()
                val activeEngine = engine() ?: return@act false
                val results = activeEngine.disable(
                    entries = listOf(request.entry),
                    catalog = current.catalog,
                    states = current.lines.associate { it.entry.packageName to it.state },
                    launchersAvailable = current.info.thirdPartyLaunchers.isNotEmpty(),
                )
                val result = results.single()
                show(
                    if (result.succeeded) context.getString(R.string.apps_disabled_done, request.application.name) else failure(result.text(context)),
                )
                if (SupportInvitation.deserves(results)) thank()
                result.succeeded
            }

            is ApplyConfirmation.Uninstallation -> act(request.application) {
                val result = actions.uninstall(request.application)
                show(
                    if (result.succeeded) context.getString(R.string.apps_uninstalled, request.application.name) else failure(result.text(context)),
                )
                result.succeeded
            }
        }
    }

    /** One action at a time. If [block] returns true, the list and the Packages tab are reloaded. */
    private fun act(application: DeviceApplication, block: suspend () -> Boolean) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = application.packageName, chosen = null) }
        scope.launch {
            val change = runCatching { block() }.getOrDefault(false)
            _state.update { it.copy(busy = null) }
            if (change) {
                refreshPackages()
                load()
            }
        }
    }

    private fun failure(reason: String): String = context.getString(R.string.apps_failed, reason)
}
