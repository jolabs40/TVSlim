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
import net.jolabs40.tvslim.startlight.StartLightAbsence
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
    /** The door does not answer: StartLight not started (HOME starts it), or a version without the door. */
    val absence: StartLightAbsence? = null,
    /** A command is on its way: one at a time, as StartLight takes them. */
    val busy: Boolean = false,
    val notice: StartLightNotice? = null,
)

/**
 * StartLight's own buttons, through the content provider it opens to the shell user (`<package>.remote`).
 *
 * A command is not idempotent (a doubled "next background" draws two images): it goes out once through the helper,
 * never through the replaying main session. The state is read when the link is ready, after each command, and every
 * two seconds while the remote is shown: its shortcuts follow the TV, also when it is driven with its own remote.
 */
class StartLightController(
    private val remote: RemoteControlController,
    private val scope: CoroutineScope,
    /** Whether a package's process runs on the TV: a read on the main session. */
    private val running: suspend (packageName: String) -> Boolean,
) {

    private val _state = MutableStateFlow(StartLightUi())
    val state: StateFlow<StartLightUi> = _state.asStateFlow()

    /** The loop reading the state again while the link is up. */
    private var following: Job? = null

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
        if (readOnce()) follow()
    }

    /** Reads the state; returns whether reading again is worth it: not without the link, nor without the door. */
    private suspend fun readOnce(): Boolean {
        val home = _state.value.home ?: return false
        val outcome = remote.call(home.authority, StartLightRemote.STATE)
        val read = StartLightRemote.state(outcome)
        // Reached the TV but no door answered. A lost call keeps the last state.
        val absence = if (read == null && outcome is CallOutcome.Failed) StartLightAbsence.of(running(home.packageName)) else null
        if (read == null) Log.w(TAG, "State not read: $outcome" + (absence?.let { ", $it" } ?: ""))
        _state.update {
            when {
                it.home != home -> it
                read != null -> it.copy(state = read, absence = null)
                absence != null -> it.copy(state = null, absence = absence)
                else -> it
            }
        }
        return outcome !is CallOutcome.NotSent && absence != StartLightAbsence.NO_DOOR
    }

    /** Stops when the link is gone, the remote closed: the call is then refused and nothing is read. */
    private fun follow() {
        if (following?.isActive == true) return
        following = scope.launch {
            while (true) {
                delay(POLL_MS)
                if (remote.state.value.phase != RemotePhase.READY) return@launch
                if (!readOnce()) return@launch
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

        const val POLL_MS = 2_000L
    }
}
