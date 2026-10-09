package net.jolabs40.tvslim.windows.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.screen.RecordingStop
import net.jolabs40.tvslim.screen.ScreenCapture
import net.jolabs40.tvslim.screen.CaptureCause
import net.jolabs40.tvslim.screen.RecordingCause
import net.jolabs40.tvslim.screen.RecordingStart
import net.jolabs40.tvslim.screen.TvRecording
import net.jolabs40.tvslim.screen.CaptureResult
import net.jolabs40.tvslim.shell.BinaryReader
import net.jolabs40.tvslim.windows.screen.ScrcpyArguments
import net.jolabs40.tvslim.windows.screen.UnexpectedFingerprint
import net.jolabs40.tvslim.windows.screen.ScrcpyInstallation
import net.jolabs40.tvslim.windows.screen.ScrcpyLocator
import net.jolabs40.tvslim.windows.screen.SystemClipboard
import net.jolabs40.tvslim.windows.screen.ScrcpySession
import net.jolabs40.tvslim.windows.tools.AppLog
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.capture_copied
import net.jolabs40.tvslim.windows.resources.capture_failed_connection
import net.jolabs40.tvslim.windows.resources.capture_failed_refused
import net.jolabs40.tvslim.windows.resources.capture_failed_unreadable
import net.jolabs40.tvslim.windows.resources.capture_write_failed
import net.jolabs40.tvslim.windows.resources.msg_connect_first
import net.jolabs40.tvslim.windows.resources.record_copy_failed
import net.jolabs40.tvslim.windows.resources.record_empty
import net.jolabs40.tvslim.windows.resources.record_failed_start
import net.jolabs40.tvslim.windows.resources.record_limit
import net.jolabs40.tvslim.windows.resources.record_unavailable
import net.jolabs40.tvslim.windows.resources.scrcpy_download_failed
import net.jolabs40.tvslim.windows.resources.scrcpy_failed
import net.jolabs40.tvslim.windows.resources.scrcpy_fingerprint_failed
import net.jolabs40.tvslim.windows.resources.scrcpy_unauthorized
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime

/** Target TV: address for scrcpy, device info for file names. */
data class ScreenTarget(val host: String, val port: Int, val info: DeviceInfo)

/** A saved screenshot, shown in a preview. */
class CompletedCapture(val file: File, val png: ByteArray, val width: Int, val height: Int)

sealed interface ScrcpyPhase {
    data object Inactive : ScrcpyPhase

    /** scrcpy was missing and is being downloaded from GitHub. */
    data class Downloading(val progress: Float) : ScrcpyPhase

    /** Launched; its window is opening or open. */
    data object Active : ScrcpyPhase
}

sealed interface RecordingPhase {
    data object Inactive : RecordingPhase

    data object RecordingStart : RecordingPhase

    /** [limitSeconds]: time limit before Android 14, after which the recorder stops on its own. */
    data class InProgress(val startedAt: Long, val limitSeconds: Int?) : RecordingPhase

    /** The recorder is stopping and finishing the video file on the TV. */
    data object RecordingStop : RecordingPhase

    data class Copy(val progress: Float) : RecordingPhase
}

data class ScreenState(
    val captureInProgress: Boolean = false,
    val capture: CompletedCapture? = null,
    val scrcpy: ScrcpyPhase = ScrcpyPhase.Inactive,
    /** Title of the requested mirror while the scrcpy download is being offered. */
    val offeredDownload: String? = null,
    val recording: RecordingPhase = RecordingPhase.Inactive,
    /** Video just copied to the PC. */
    val video: File? = null,
    /** TV Slim quits as soon as the current video has arrived. */
    val closing: Boolean = false,
    val message: UiMessage? = null,
)

/**
 * The TV screen from the PC:
 *
 * - screenshot, over TV Slim's ADB session;
 * - video, recorded on the TV by its own `screenrecord`, then copied over the same session (`TvRecording`),
 *   without audio;
 * - mirror, through scrcpy, which opens its own window and session with its own key, so the TV asks once to
 *   authorize it. TV Slim does not lend its own key, which never leaves the app decrypted.
 *
 * Recording closes the mirror: one or the other, never both.
 */
class ScreenController(
    private val reader: BinaryReader,
    private val recording: TvRecording,
    private val locator: ScrcpyLocator,
    private val installation: ScrcpyInstallation,
    /** `null` while no TV is connected. */
    private val target: () -> ScreenTarget?,
    private val picturesFolder: () -> File,
    private val videosFolder: () -> File,
) : ViewModel() {

    private val _state = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = _state.asStateFlow()

    private var session: ScrcpySession? = null

    /** Watches for the recorder ending: limit reached or unexpected stop. */
    private var watchJob: Job? = null
    private var end: Job? = null

    /** TV info and start time of the recording, used to name the video. */
    private var recordingInfo: Pair<DeviceInfo, LocalDateTime>? = null

    private var quit: (() -> Unit)? = null

    // --- Screenshot -------------------------------------------------------------------------

    fun takeCapture() {
        val targetTv = target() ?: return show(text(Res.string.msg_connect_first))
        if (_state.value.captureInProgress) return
        _state.update { it.copy(captureInProgress = true) }
        viewModelScope.launch {
            val message = when (val result = ScreenCapture(reader).takeCapture()) {
                is CaptureResult.Succeeded -> saveCapture(result, targetTv.info)
                is CaptureResult.Failed -> failureMessage(result)
            }
            _state.update { it.copy(captureInProgress = false) }
            message?.let(::show)
        }
    }

    /** Writes the screenshot to `Pictures\TV Slim`; returns a message only if writing fails. */
    private suspend fun saveCapture(result: CaptureResult.Succeeded, info: DeviceInfo): UiMessage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(picturesFolder(), SUBFOLDER).apply { mkdirs() }
                val file = File(folder, ScreenCapture.fileName(info, LocalDateTime.now(), "png"))
                file.writeBytes(result.png)
                CompletedCapture(file, result.png, result.width, result.height)
            }.fold(
                onSuccess = { taken ->
                    _state.update { it.copy(capture = taken) }
                    null
                },
                onFailure = { text(Res.string.capture_write_failed, UiMessage.Raw(it.message.orEmpty())) },
            )
        }

    private fun failureMessage(failure: CaptureResult.Failed): UiMessage = when (failure.cause) {
        CaptureCause.CONNECTION -> text(Res.string.capture_failed_connection, UiMessage.Raw(failure.detail))
        CaptureCause.REJECTED -> text(Res.string.capture_failed_refused, UiMessage.Raw(failure.detail))
        CaptureCause.UNREADABLE -> text(Res.string.capture_failed_unreadable, UiMessage.Raw(failure.detail))
    }

    fun copyCapture() {
        val capture = _state.value.capture ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SystemClipboard.copyImage(capture.png) } }
                .onSuccess { show(text(Res.string.capture_copied)) }
                .onFailure { show(text(Res.string.capture_write_failed, UiMessage.Raw(it.message.orEmpty()))) }
        }
    }

    fun closeCapture() = _state.update { it.copy(capture = null) }

    // --- Video, on the TV ---------------------------------------------------------------------

    fun record() {
        val targetTv = target() ?: return show(text(Res.string.msg_connect_first))
        if (_state.value.recording != RecordingPhase.Inactive) return
        _state.update { it.copy(recording = RecordingPhase.RecordingStart) }
        viewModelScope.launch {
            // One or the other: the mirror closes when recording starts.
            session?.let { mirror -> withContext(Dispatchers.IO) { mirror.stop() } }
            when (val startResult = recording.start()) {
                is RecordingStart.Started -> {
                    recordingInfo = targetTv.info to LocalDateTime.now()
                    _state.update {
                        it.copy(recording = RecordingPhase.InProgress(System.currentTimeMillis(), startResult.limitSeconds))
                    }
                    if (startResult.limitSeconds != null) show(text(Res.string.record_limit))
                    watchRecorder()
                    if (_state.value.closing) finish()
                }

                is RecordingStart.Rejected -> {
                    _state.update { it.copy(recording = RecordingPhase.Inactive) }
                    show(failureMessage(startResult.cause, startResult.detail))
                    quitIfRequested()
                }
            }
        }
    }

    /** Checks every two seconds whether the recorder stopped on its own; no answer means try again next time. */
    private fun watchRecorder() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (isActive) {
                delay(WATCH_POLL_MS)
                if (recording.isAlive() == false) {
                    finish()
                    return@launch
                }
            }
        }
    }

    fun stopRecording() = finish()

    /** Stops the recorder, copies the video to `Videos\TV Slim`, then deletes it from the TV. */
    private fun finish() {
        if (_state.value.recording !is RecordingPhase.InProgress || end?.isActive == true) return
        watchJob?.cancel()
        _state.update { it.copy(recording = RecordingPhase.RecordingStop) }
        end = viewModelScope.launch {
            when (val stopResult = recording.stop()) {
                is RecordingStop.Done -> copy(stopResult.size)
                is RecordingStop.Rejected -> show(failureMessage(stopResult.cause, stopResult.detail))
            }
            _state.update { it.copy(recording = RecordingPhase.Inactive) }
            quitIfRequested()
        }
    }

    private suspend fun copy(size: Long) {
        val (info, startedAt) = recordingInfo ?: (DeviceInfo.EMPTY to LocalDateTime.now())
        _state.update { it.copy(recording = RecordingPhase.Copy(0f)) }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(videosFolder(), SUBFOLDER).apply { mkdirs() }
                val file = File(folder, ScreenCapture.fileName(info, startedAt, "mp4"))
                val partial = File(folder, file.name + ".partiel")
                var percentStep = -1
                val result = partial.outputStream().buffered().use { stream ->
                    recording.download(stream, size, onReceived = { received ->
                        // Update once per percent, not thousands of recompositions per copy.
                        val percent = (received * 100 / size.coerceAtLeast(1)).toInt()
                        if (percent != percentStep) {
                            percentStep = percent
                            _state.update { it.copy(recording = RecordingPhase.Copy(received.toFloat() / size)) }
                        }
                    })
                }
                if (!result.succeeded) {
                    partial.delete()
                    error(result.output)
                }
                Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                file
            }
        }
        outcome.onSuccess { file ->
            recording.clean()
            if (!_state.value.closing) _state.update { it.copy(video = file) }
        }.onFailure { error ->
            AppLog.warn(TAG, "Vidéo non copiée", error)
            // The video stays on the TV; the next recording replaces it.
            show(text(Res.string.record_copy_failed, UiMessage.Raw(error.message.orEmpty()), TvRecording.VIDEO))
        }
    }

    private fun failureMessage(cause: RecordingCause, detail: String): UiMessage = when (cause) {
        RecordingCause.CONNECTION -> text(Res.string.capture_failed_connection, UiMessage.Raw(detail))
        RecordingCause.UNAVAILABLE -> text(Res.string.record_unavailable, UiMessage.Raw(detail))
        RecordingCause.FAILED -> text(Res.string.record_failed_start, UiMessage.Raw(detail))
        RecordingCause.EMPTY -> text(Res.string.record_empty, UiMessage.Raw(detail))
    }

    fun closeVideo() = _state.update { it.copy(video = null) }

    // --- Mirror, via scrcpy ---------------------------------------------------------------------

    /** Opens the mirror, offering to download scrcpy first if it is missing. */
    fun openMirror(title: String) {
        if (target() == null) return show(text(Res.string.msg_connect_first))
        if (_state.value.scrcpy != ScrcpyPhase.Inactive || _state.value.recording != RecordingPhase.Inactive) return
        viewModelScope.launch {
            val exe = withContext(Dispatchers.IO) { locator.find() }
            if (exe == null) {
                _state.update { it.copy(offeredDownload = title) }
            } else {
                start(exe, title)
            }
        }
    }

    fun declineDownload() = _state.update { it.copy(offeredDownload = null) }

    fun acceptDownload() {
        val title = _state.value.offeredDownload ?: return
        if (_state.value.scrcpy != ScrcpyPhase.Inactive) return
        _state.update { it.copy(scrcpy = ScrcpyPhase.Downloading(0f)) }
        viewModelScope.launch {
            runCatching {
                installation.install { progress -> _state.update { it.copy(scrcpy = ScrcpyPhase.Downloading(progress)) } }
            }.onSuccess { exe ->
                _state.update { it.copy(scrcpy = ScrcpyPhase.Inactive, offeredDownload = null) }
                start(exe, title)
            }.onFailure { error ->
                AppLog.warn(TAG, "scrcpy non téléchargé", error)
                _state.update { it.copy(scrcpy = ScrcpyPhase.Inactive, offeredDownload = null) }
                show(
                    if (error is UnexpectedFingerprint) {
                        text(Res.string.scrcpy_fingerprint_failed)
                    } else {
                        text(Res.string.scrcpy_download_failed, UiMessage.Raw(error.message.orEmpty()))
                    },
                )
            }
        }
    }

    private suspend fun start(exe: File, title: String) {
        val targetTv = target() ?: return show(text(Res.string.msg_connect_first))
        val launched = withContext(Dispatchers.IO) {
            runCatching { ScrcpySession.start(exe, ScrcpyArguments.mirror(targetTv.host, targetTv.port, title)) }
        }.getOrElse { error ->
            AppLog.warn(TAG, "scrcpy non lancé", error)
            return show(text(Res.string.scrcpy_failed, UiMessage.Raw(error.message.orEmpty())))
        }
        session = launched
        _state.update { it.copy(scrcpy = ScrcpyPhase.Active) }
        viewModelScope.launch {
            val code = launched.waitFor()
            if (session === launched) session = null
            _state.update { it.copy(scrcpy = ScrcpyPhase.Inactive) }
            if (code != 0) show(scrcpyFailure(launched.output))
        }
    }

    private fun scrcpyFailure(output: List<String>): UiMessage {
        if (output.any { it.contains("unauthorized", ignoreCase = true) }) return text(Res.string.scrcpy_unauthorized)
        val reason = output.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: output.lastOrNull { it.isNotBlank() }.orEmpty()
        return text(Res.string.scrcpy_failed, UiMessage.Raw(reason))
    }

    /** Closes the scrcpy window the way a mouse click would. */
    fun stopMirror() {
        val active = session ?: return
        viewModelScope.launch(Dispatchers.IO) { active.stop() }
    }

    // --- Closing TV Slim ------------------------------------------------------------------------

    /**
     * Closes the mirror, then calls [quit], right away or once the current video has reached the PC rather
     * than leaving it on the TV with the recorder still running.
     */
    fun close(quit: () -> Unit) {
        session?.stop()
        if (_state.value.recording == RecordingPhase.Inactive) return quit()
        this.quit = quit
        _state.update { it.copy(closing = true) }
        finish()
    }

    private fun quitIfRequested() {
        if (_state.value.closing) quit?.invoke()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun show(message: UiMessage) = _state.update { it.copy(message = message) }

    private companion object {
        const val TAG = "Ecran"
        const val SUBFOLDER = "TV Slim"
        const val WATCH_POLL_MS = 2_000L
    }
}
