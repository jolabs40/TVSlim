package net.jolabs40.tvslim.windows.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.update_check_failed
import net.jolabs40.tvslim.windows.resources.update_failed
import net.jolabs40.tvslim.windows.resources.update_signature_invalid
import net.jolabs40.tvslim.windows.ui.UiMessage
import net.jolabs40.tvslim.windows.ui.text

sealed interface UpdatePhase {
    data object Inactive : UpdatePhase
    data object Checking : UpdatePhase
    data object UpToDate : UpdatePhase
    data class Available(val update: AvailableUpdate) : UpdatePhase
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdatePhase
    data class Verification(val update: AvailableUpdate) : UpdatePhase
    data class Installation(val update: AvailableUpdate) : UpdatePhase
    data class Failure(val message: UiMessage, val update: AvailableUpdate?) : UpdatePhase
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.Inactive,
    val autoCheck: Boolean = true,
    val mode: DistributionMode = DistributionMode.DEVELOPMENT,
    val currentVersion: String = "",
    /** "Later" hides the banner for this session. */
    val bannerDismissed: Boolean = false,
) {
    /** The update in progress, whatever the phase. */
    val currentUpdate: AvailableUpdate?
        get() = when (val p = phase) {
            is UpdatePhase.Available -> p.update
            is UpdatePhase.Downloading -> p.update
            is UpdatePhase.Verification -> p.update
            is UpdatePhase.Installation -> p.update
            is UpdatePhase.Failure -> p.update
            else -> null
        }
}

/**
 * Update checks and installs.
 *
 * Checks GitHub at startup (can be turned off in About). Nothing is downloaded until the user clicks Install,
 * and nothing is installed without a valid signature. Portable and development builds open the release page
 * instead of installing.
 */
class UpdatesController(
    private val preferences: WindowsPreferences,
    private val client: GithubClient,
    private val installer: UpdateInstaller,
    private val distribution: Distribution,
    private val currentVersion: Version,
    private val repository: String,
    private val quit: () -> Unit,
    private val openLink: (String) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(
        UpdateState(mode = distribution.mode, currentVersion = currentVersion.toString()),
    )
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var work: Job? = null

    init {
        viewModelScope.launch {
            val fetched = preferences.read()
            _state.update { it.copy(autoCheck = fetched.checkForUpdates) }
            if (fetched.checkForUpdates && distribution.mode != DistributionMode.DEVELOPMENT) {
                check(quiet = true)
            }
        }
    }

    /** [quiet]: the startup check stays silent when up to date or when GitHub is unreachable. */
    fun check(quiet: Boolean = false) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(phase = UpdatePhase.Checking) }
            val outcome = try {
                Result.success(ReleaseChoice.choose(client.releases(), currentVersion, repository))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure(error)
            }
            _state.update { current ->
                outcome.fold(
                    onSuccess = { update ->
                        when {
                            update != null -> current.copy(phase = UpdatePhase.Available(update), bannerDismissed = false)
                            quiet -> current
                            else -> current.copy(phase = UpdatePhase.UpToDate)
                        }
                    },
                    onFailure = { error ->
                        if (quiet) {
                            current
                        } else {
                            current.copy(phase = UpdatePhase.Failure(failure(Res.string.update_check_failed, error), null))
                        }
                    },
                )
            }
        }
    }

    /** Installs the update, or opens its download page when not running from the MSI install. */
    fun install() {
        val update = _state.value.currentUpdate ?: return
        val executable = distribution.executable
        if (distribution.mode != DistributionMode.INSTALLED || executable == null) {
            openLink(update.page)
            return
        }
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            _state.update { it.copy(phase = UpdatePhase.Downloading(update, 0f)) }
            try {
                val msi = installer.prepare(
                    update = update,
                    onProgress = { p -> _state.update { it.copy(phase = UpdatePhase.Downloading(update, p)) } },
                    onVerification = { _state.update { it.copy(phase = UpdatePhase.Verification(update)) } },
                )
                _state.update { it.copy(phase = UpdatePhase.Installation(update)) }
                installer.installThenRelaunch(msi, executable)
                // Leaves time to read the restart message; the relay waits for us to exit.
                delay(CLOSE_DELAY_MS)
                quit()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (invalid: UpdateInstaller.InvalidSignature) {
                _state.update { it.copy(phase = UpdatePhase.Failure(text(Res.string.update_signature_invalid), update)) }
            } catch (error: Exception) {
                _state.update { it.copy(phase = UpdatePhase.Failure(failure(Res.string.update_failed, error), update)) }
            }
        }
    }

    fun openPage() = openLink(_state.value.currentUpdate?.page ?: "https://github.com/$repository/releases")

    fun dismiss() = _state.update { it.copy(bannerDismissed = true) }

    fun setAutoCheck(active: Boolean) {
        _state.update { it.copy(autoCheck = active) }
        viewModelScope.launch { preferences.setUpdateCheck(active) }
    }

    private fun failure(resource: org.jetbrains.compose.resources.StringResource, error: Throwable) =
        text(resource, error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)

    private companion object {
        const val CLOSE_DELAY_MS = 1_500L
    }
}
