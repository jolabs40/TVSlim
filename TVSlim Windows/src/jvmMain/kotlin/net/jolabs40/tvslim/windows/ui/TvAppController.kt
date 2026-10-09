package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.tvapp.TvApp
import net.jolabs40.tvslim.tvapp.TvStep
import net.jolabs40.tvslim.tvapp.TvReason
import net.jolabs40.tvslim.tvapp.TvRelease
import net.jolabs40.tvslim.tvapp.TvResult
import net.jolabs40.tvslim.tvapp.TvSituation
import net.jolabs40.tvslim.tvapp.GithubSource
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.windows.AppInfo
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.apk_cause_other
import net.jolabs40.tvslim.windows.resources.tvapp_done
import net.jolabs40.tvslim.windows.resources.tvapp_done_guardian_manual
import net.jolabs40.tvslim.windows.resources.tvapp_done_no_permission
import net.jolabs40.tvslim.windows.resources.tvapp_error_certificate
import net.jolabs40.tvslim.windows.resources.tvapp_error_install
import net.jolabs40.tvslim.windows.resources.tvapp_error_network
import net.jolabs40.tvslim.windows.resources.tvapp_error_not_found
import net.jolabs40.tvslim.windows.resources.tvapp_error_package
import net.jolabs40.tvslim.windows.resources.tvapp_error_sdk
import net.jolabs40.tvslim.windows.resources.tvapp_error_too_big
import net.jolabs40.tvslim.windows.resources.tvapp_error_tv
import java.io.File

/** State of the "TV Slim on the TV" card. */
data class TvAppUiState(
    /** Null until the TV has been read. */
    val situation: TvSituation? = null,
    /** Current install step; null when idle. */
    val step: TvStep? = null,
) {
    val busy: Boolean get() = step != null
}

data class TvAppActions(
    val onRead: () -> Unit,
    val onInstall: () -> Unit,
    val onAuthorize: () -> Unit,
)

/**
 * The TV Slim app on the TV: installed version, and installing it from GitHub (see the core's `TvApp`).
 * Same controller as on the phone. GitHub is queried only when the card is shown for a TV or box, once per run.
 */
class TvAppController(
    private val client: AdbClient,
    private val reader: RemoteReader,
    private val engine: () -> DebloatEngine?,
    private val installation: () -> ApkInstallation?,
    private val folder: File,
    private val scope: CoroutineScope,
    private val show: (UiMessage) -> Unit,
    private val thank: () -> Unit,
) {

    private val _state = MutableStateFlow(TvAppUiState())
    val state: StateFlow<TvAppUiState> = _state.asStateFlow()

    private val source = GithubSource(agent = "TVSlim-Windows/${AppInfo.VERSION}")
    private var last: TvRelease? = null
    private var requested = false
    private var work: Job? = null

    private fun application(): TvApp? {
        val activeEngine = engine() ?: return null
        val activeInstallation = installation() ?: return null
        return TvApp(
            executor = client,
            installation = activeInstallation,
            engine = activeEngine,
            reader = reader,
            source = source,
            expectedFingerprint = AppInfo.ANDROID_CERTIFICATE_FINGERPRINT,
            folder = folder,
        )
    }

    fun read() {
        val application = application() ?: return
        if (work?.isActive == true) return
        work = scope.launch {
            if (!requested) {
                requested = true
                last = application.last()
            }
            val situation = application.situation(last)
            _state.update { it.copy(situation = situation) }
        }
    }

    fun install() = start { application, step -> application.install(step) }

    fun authorize() {
        val version = _state.value.situation?.installed?.versionName ?: return
        start { application, step -> application.authorize(version, step) }
    }

    /** Forgets what was read, when the TV changes or the connection drops. */
    fun forget() {
        work?.cancel()
        _state.value = TvAppUiState()
    }

    private fun start(action: suspend (TvApp, (TvStep) -> Unit) -> TvResult) {
        val application = application() ?: return
        if (_state.value.busy) return
        work?.cancel()
        work = scope.launch {
            _state.update { it.copy(step = TvStep.Checking) }
            val result = try {
                action(application) { step -> _state.update { it.copy(step = step) } }
            } finally {
                _state.update { it.copy(step = null) }
            }
            show(message(result))
            if (result is TvResult.Succeeded) thank()
            // GitHub may have failed on the first read while the install reached it.
            if (last == null) last = application.last()
            _state.update { it.copy(situation = application.situation(last)) }
        }
    }

    private fun message(result: TvResult): UiMessage = when (result) {
        is TvResult.Succeeded -> text(
            when {
                !result.authorized -> Res.string.tvapp_done_no_permission
                result.guardian -> Res.string.tvapp_done
                else -> Res.string.tvapp_done_guardian_manual
            },
            result.version,
        )

        is TvResult.Failed -> when (result.reason) {
            TvReason.INSTALLATION -> text(
                Res.string.tvapp_error_install,
                text(result.cause?.resource() ?: Res.string.apk_cause_other),
            )
            TvReason.NOT_FOUND -> text(Res.string.tvapp_error_not_found)
            TvReason.NETWORK -> text(Res.string.tvapp_error_network)
            TvReason.TOO_BIG -> text(Res.string.tvapp_error_too_big)
            TvReason.CERTIFICATE -> text(Res.string.tvapp_error_certificate)
            TvReason.PACKAGE_NAME -> text(Res.string.tvapp_error_package)
            TvReason.ANDROID_TOO_OLD -> text(Res.string.tvapp_error_sdk)
            TvReason.TV_UNREACHABLE -> text(Res.string.tvapp_error_tv)
        }
    }
}
