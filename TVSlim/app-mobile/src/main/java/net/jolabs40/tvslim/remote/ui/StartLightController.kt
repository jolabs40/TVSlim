package net.jolabs40.tvslim.remote.ui

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.control.CallFailure
import net.jolabs40.tvslim.control.CallOutcome
import net.jolabs40.tvslim.control.RemoteKey
import net.jolabs40.tvslim.startlight.StartLightCommand
import net.jolabs40.tvslim.startlight.StartLightHome
import net.jolabs40.tvslim.startlight.StartLightRefusal
import net.jolabs40.tvslim.startlight.StartLightRemote
import net.jolabs40.tvslim.startlight.StartLightReply
import net.jolabs40.tvslim.startlight.StartLightState

/** What the StartLight section says after a command, in plain words: nothing is refused silently. */
sealed interface StartLightNotice {
    data class Refused(val refusal: StartLightRefusal, val reason: String) : StartLightNotice

    data class NotDelivered(val failure: CallFailure) : StartLightNotice

    data class Failed(val detail: String) : StartLightNotice
}

data class StartLightUi(
    /** StartLight is the TV's home; null hides the section. */
    val home: StartLightHome? = null,
    /** Last state read; its buttons are the only ones shown. */
    val state: StartLightState? = null,
    /** The state could not be read from this StartLight: a version without the door. */
    val withoutDoor: Boolean = false,
    /** A command is on its way: one at a time, as StartLight takes them. */
    val busy: Boolean = false,
    val notice: StartLightNotice? = null,
)

/**
 * StartLight's own buttons, through the content provider it opens to the shell user (`<package>.remote`).
 *
 * A command is not idempotent (a doubled "next background" draws two images): it goes out once through the helper,
 * never through the replaying main session. The state is read again when the link is ready and after each command.
 */
class StartLightController(
    private val remote: RemoteControlController,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(StartLightUi())
    val state: StateFlow<StartLightUi> = _state.asStateFlow()

    /** The loop reading the state again while StartLight cannot take commands. */
    private var waitingForReady: Job? = null

    init {
        scope.launch {
            remote.state
                .map { (it.phase == RemotePhase.READY) to it.startLight }
                .distinctUntilChanged()
                .collect { (ready, home) ->
                    if (home == null) {
                        _state.value = StartLightUi()
                    } else {
                        if (home != _state.value.home) _state.value = StartLightUi(home = home)
                        if (ready) read()
                    }
                }
        }
    }

    fun send(command: StartLightCommand) {
        val home = _state.value.home ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        scope.launch {
            val reply = StartLightRemote.reply(remote.call(home.authority, command.method, command.argument))
            Log.i(TAG, "${command.method} ${command.argument.orEmpty()}: $reply")
            _state.update { it.copy(busy = false, notice = notice(reply)) }
            read()
        }
    }

    /**
     * StartLight never brings itself forward, which Android forbids to a home in the background: HOME does, then the
     * person presses the button again. The command is not resent by itself.
     */
    fun bringHome() {
        if (!remote.tap(RemoteKey.HOME)) return
        _state.update { it.copy(notice = null) }
        scope.launch {
            delay(HOME_SETTLE_MS)
            read()
        }
    }

    private suspend fun read() {
        val state = readOnce()
        if (state != null && waiting(state)) readUntilAvailable()
    }

    private suspend fun readOnce(): StartLightState? {
        val home = _state.value.home ?: return null
        val outcome = remote.call(home.authority, StartLightRemote.STATE)
        val read = StartLightRemote.state(outcome)
        if (read == null) Log.w(TAG, "State not read: $outcome")
        _state.update {
            when {
                it.home != home -> it
                read != null -> it.copy(state = read, withoutDoor = false)
                // Reached the TV but no door answered: an older StartLight. A lost call keeps the last state.
                outcome is CallOutcome.Failed -> it.copy(state = null, withoutDoor = true)
                else -> it
            }
        }
        return read
    }

    /** Starting, under a welcome screen, or behind another app: its buttons would not act yet. */
    private fun waiting(state: StartLightState) = !state.ready || !state.foreground

    /**
     * Reads again until StartLight takes commands, so the section follows what happens on the TV without a command.
     * Stops when the link is gone: the call is then refused and nothing is read.
     */
    private fun readUntilAvailable() {
        if (waitingForReady?.isActive == true) return
        waitingForReady = scope.launch {
            while (true) {
                delay(WAITING_POLL_MS)
                if (remote.state.value.phase != RemotePhase.READY) return@launch
                val state = readOnce() ?: return@launch
                if (!waiting(state)) return@launch
            }
        }
    }

    private fun notice(reply: StartLightReply): StartLightNotice? = when (reply) {
        StartLightReply.Done -> null
        is StartLightReply.Refused -> StartLightNotice.Refused(reply.refusal, reply.reason)
        is StartLightReply.NotDelivered -> StartLightNotice.NotDelivered(reply.failure)
        is StartLightReply.Failed -> StartLightNotice.Failed(reply.detail)
    }

    private companion object {
        const val TAG = "TVSlim/StartLight"

        /** Time for the home to come forward before its state is read again. */
        const val HOME_SETTLE_MS = 700L

        const val WAITING_POLL_MS = 2_000L
    }
}
