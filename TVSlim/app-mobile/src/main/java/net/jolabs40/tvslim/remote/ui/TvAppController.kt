package net.jolabs40.tvslim.remote.ui

import android.content.Context
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
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.AdbClient
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
 * Reads which TV Slim app the TV has and installs it from GitHub (see the core's `TvApp`).
 *
 * GitHub is queried at most once per app launch, and only when the card is shown (on a TV or a box).
 */
class TvAppController(
    private val context: Context,
    private val client: AdbClient,
    private val reader: RemoteReader,
    private val engine: () -> DebloatEngine?,
    private val installation: () -> ApkInstallation?,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val thank: () -> Unit,
) {

    private val _state = MutableStateFlow(TvAppUiState())
    val state: StateFlow<TvAppUiState> = _state.asStateFlow()

    private val source = GithubSource(agent = "TVSlim-Remote/${BuildConfig.VERSION_NAME}")
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
            expectedFingerprint = BuildConfig.CERTIFICATE_FINGERPRINT,
            folder = File(context.cacheDir, "application-tv"),
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

    /** Drops what was read, on TV change or disconnection. */
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
            show(text(result))
            if (result is TvResult.Succeeded) thank()
            // GitHub may have failed on the first read but answered during the install.
            if (last == null) last = application.last()
            _state.update { it.copy(situation = application.situation(last)) }
        }
    }

    private fun text(result: TvResult): String = when (result) {
        is TvResult.Succeeded -> context.getString(
            when {
                !result.authorized -> R.string.tvapp_done_no_permission
                result.guardian -> R.string.tvapp_done
                else -> R.string.tvapp_done_guardian_manual
            },
            result.version,
        )

        is TvResult.Failed -> when (result.reason) {
            TvReason.INSTALLATION -> context.getString(
                R.string.tvapp_error_install,
                context.getString(result.cause?.resource() ?: R.string.apk_cause_other),
            )
            else -> context.getString(
                when (result.reason) {
                    TvReason.NOT_FOUND -> R.string.tvapp_error_not_found
                    TvReason.NETWORK -> R.string.tvapp_error_network
                    TvReason.TOO_BIG -> R.string.tvapp_error_too_big
                    TvReason.CERTIFICATE -> R.string.tvapp_error_certificate
                    TvReason.PACKAGE_NAME -> R.string.tvapp_error_package
                    TvReason.ANDROID_TOO_OLD -> R.string.tvapp_error_sdk
                    else -> R.string.tvapp_error_tv
                },
            )
        }
    }
}
