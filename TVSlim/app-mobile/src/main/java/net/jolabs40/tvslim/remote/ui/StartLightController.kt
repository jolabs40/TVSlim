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

    /** HOME was pressed for a shortcut, but StartLight did not come in front, ready, in time: nothing was sent. */
    data object NotInFront : StartLightNotice

    /** Back in front, StartLight no longer offers what the shortcut sends (a page removed since): nothing was sent. */
    data object NotOffered : StartLightNotice
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
) {
    /**
     * A shortcut to StartLight's buttons can act: it is in front and ready, or a HOME away, behind another app or not
     * running (HOME starts it). Not over its own permission request, which HOME would close unanswered.
     */
    val reachable: Boolean
        get() = home != null && !busy && when (state) {
            null -> absence == StartLightAbsence.NOT_STARTED
            else -> state.ready && (state.foreground || state.behindAnotherApp)
        }
}

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
        scope.launch { finish(notice(call(home, command))) }
    }

    /**
     * A shortcut: what [pick] chooses from StartLight's state. Behind another app or not running, HOME comes first and
     * leaves that app, unless StartLight takes that command from behind. Sent once at most: a refusal did nothing, a
     * command that ran is never sent again.
     */
    fun sendInFront(pick: (StartLightState) -> StartLightCommand?) {
        val ui = _state.value
        val home = ui.home
        if (home == null || !ui.reachable) return
        _state.update { it.copy(busy = true, notice = null) }
        scope.launch {
            val shown = ui.state
            if (shown != null) {
                val command = pick(shown) ?: return@launch finish(StartLightNotice.NotOffered)
                if (shown.foreground || !needsForeground(shown, command)) {
                    val reply = call(home, command)
                    // The state was read up to two seconds ago: an app may have come in front since.
                    if ((reply as? StartLightReply.Refused)?.refusal != StartLightRefusal.NOT_FOREGROUND) return@launch finish(notice(reply))
                }
            }
            if (!remote.tap(RemoteKey.HOME)) return@launch finish(StartLightNotice.NotDelivered(CallFailure.NOT_READY))
            val reached = awaitInFront() ?: return@launch finish(StartLightNotice.NotInFront)
            // Its own request came up instead: the status line says to answer it on the TV.
            if (!reached.foreground) return@launch finish(StartLightNotice.Refused(StartLightRefusal.PERMISSION_REQUEST, ""))
            val command = pick(reached) ?: return@launch finish(StartLightNotice.NotOffered)
            finish(notice(call(home, command)))
        }
    }

    /** As StartLight's state lists it (an action as `action:<name>`); one it does not list is taken as needing it in front. */
    private fun needsForeground(state: StartLightState, command: StartLightCommand): Boolean {
        val name = if (command is StartLightCommand.Action) "${command.method}:${command.action.wire}" else command.method
        return state.commands.firstOrNull { it.name == name }?.needsForeground ?: true
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

    private suspend fun call(home: StartLightHome, command: StartLightCommand): StartLightReply {
        val reply = StartLightRemote.reply(remote.call(home.authority, command.method, command.argument))
        Log.i(TAG, "${command.method} ${command.argument.orEmpty()}: $reply")
        return reply
    }

    private suspend fun finish(notice: StartLightNotice?) {
        _state.update { it.copy(busy = false, notice = notice) }
        read()
    }

    /**
     * Reads the state after HOME until StartLight is in front and ready, or shows its own permission request. Null when
     * it does not come in time, or the link goes. A cold start takes a few seconds on a slow TV.
     */
    private suspend fun awaitInFront(): StartLightState? {
        repeat(IN_FRONT_READS) {
            delay(IN_FRONT_STEP_MS)
            if (!readOnce()) return null
            val state = _state.value.state
            if (state != null && (state.ready && state.foreground || state.permissionRequest)) return state
        }
        return null
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

        /** After HOME for a shortcut: a read every half second, for eight seconds at most. */
        const val IN_FRONT_STEP_MS = 500L
        const val IN_FRONT_READS = 16
    }
}
