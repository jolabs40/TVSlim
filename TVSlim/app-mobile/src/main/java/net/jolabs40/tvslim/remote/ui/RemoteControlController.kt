package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.applications.ApplicationsReader
import net.jolabs40.tvslim.control.ControlSession
import net.jolabs40.tvslim.control.RemoteKey
import net.jolabs40.tvslim.control.SessionEnd
import net.jolabs40.tvslim.control.SessionPhase
import net.jolabs40.tvslim.remote.adb.AdbClient
import net.jolabs40.tvslim.remote.adb.ConnectionState
import net.jolabs40.tvslim.remote.adb.ControlLink

enum class RemotePhase {
    /** No TV connected: the remote reuses the address and key of the main connection. */
    NO_TV,
    CONNECTING,
    READY,

    /** The link dropped; the presses made meanwhile are refused, nothing is sent later. */
    RECONNECTING,

    /** Gave up after a few attempts, until the person asks again. */
    UNREACHABLE,
}

data class RemoteControlUi(
    val phase: RemotePhase = RemotePhase.NO_TV,
    val latencyMs: Long? = null,
    val screenOn: Boolean? = null,
    /** Key codes held down, shown pressed. */
    val held: Set<Int> = emptySet(),
    /** The link dropped soon after "sleep": the TV has probably cut its network. */
    val wentToSleep: Boolean = false,
    /** Text the key map cannot type can be pasted. */
    val canPaste: Boolean = true,
)

/**
 * The remote control's link to the TV, alive only while its screen is shown and the app is in the foreground.
 *
 * It has its own ADB session, so a long read on the main one never delays a key, and its own reconnects: after a
 * drop it opens a new link, never resends anything, and cancels the keys the previous one left held. Going to the
 * background closes it first, before the battery saver cuts the socket.
 */
class RemoteControlController(
    private val context: Context,
    private val client: AdbClient,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RemoteControlUi())
    val state: StateFlow<RemoteControlUi> = _state.asStateFlow()

    private val _rejected = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** A press that could not go out, or that the TV dropped: the screen vibrates differently. */
    val rejected: SharedFlow<Unit> = _rejected.asSharedFlow()

    private var loop: Job? = null

    @Volatile
    private var session: ControlSession? = null

    /** Keys a dropped link left held; the next helper cancels them. */
    private var orphans: Set<Int> = emptySet()
    private var sleepSentAt = 0L

    /** The screen is shown and the app started. */
    fun show() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** The screen left, or the app went to the background. */
    fun hide() {
        val running = loop ?: return
        loop = null
        running.cancel()
    }

    fun retry() {
        hide()
        show()
    }

    fun press(key: RemoteKey): Boolean = accepted(session?.press(key))

    fun release(key: RemoteKey) {
        session?.release(key)
    }

    fun cancel(key: RemoteKey) {
        session?.cancel(key)
    }

    fun tap(key: RemoteKey): Boolean = accepted(session?.tap(key))

    fun wake(): Boolean = accepted(session?.wake())

    fun sleep(): Boolean {
        val sent = accepted(session?.sleep())
        if (sent) sleepSentAt = SystemClock.elapsedRealtime()
        return sent
    }

    fun text(text: String): Boolean = accepted(session?.text(text))

    private fun accepted(result: Boolean?): Boolean {
        if (result == null) _rejected.tryEmit(Unit)
        return result == true
    }

    private suspend fun run() {
        var failures = 0
        var dropped = false
        while (scope.isActive) {
            if (client.connection.value.state == ConnectionState.DISCONNECTED) {
                _state.update { RemoteControlUi(phase = RemotePhase.NO_TV) }
                client.connection.first { it.state != ConnectionState.DISCONNECTED }
            }
            val reopening = dropped || failures > 0
            _state.update { it.copy(phase = if (reopening) RemotePhase.RECONNECTING else RemotePhase.CONNECTING, held = emptySet()) }

            val opening = ControlLink.open(client) {
                runCatching { context.assets.open(ApplicationsReader.RESOURCE_PATH) }.getOrNull()
            }
            if (opening is ControlLink.Opening.Failed) {
                Log.w(TAG, "Remote not opened: ${opening.problem} ${opening.detail}")
                failures++
                // Just put to sleep and gone: a box that cuts its network will not come back by itself, say so now.
                val attempts = if (dropped && recentlySlept()) 1 else MAX_ATTEMPTS
                if (failures >= attempts) {
                    _state.update { it.copy(phase = RemotePhase.UNREACHABLE, wentToSleep = recentlySlept()) }
                    return
                }
                delay(BACKOFF_MS * failures)
                continue
            }
            failures = 0
            val link = (opening as ControlLink.Opening.Opened).link
            val end = drive(link)
            if (end == null || end == SessionEnd.CLOSED) return
            if (end == SessionEnd.REJECTED) {
                _state.update { it.copy(phase = RemotePhase.UNREACHABLE) }
                return
            }
            Log.i(TAG, "Remote link ended: $end")
            dropped = true
        }
    }

    /** Runs one session to its end; returns how it ended, or null if the screen left. */
    private suspend fun drive(link: ControlLink): SessionEnd? {
        val active = ControlSession(link, scope, SystemClock::elapsedRealtime, orphans)
        session = active
        val mirror = scope.launch {
            active.state.collect { current ->
                _state.update {
                    it.copy(
                        phase = when (current.phase) {
                            SessionPhase.READY -> RemotePhase.READY
                            else -> it.phase
                        },
                        latencyMs = current.latencyMs ?: it.latencyMs,
                        screenOn = current.screenOn,
                        held = current.held,
                        canPaste = current.ready?.clipboard ?: it.canPaste,
                        wentToSleep = false,
                    )
                }
            }
        }
        val trips = scope.launch { active.roundTrips.collect { Log.d(TAG, "round trip $it ms") } }
        val drops = scope.launch { active.rejected.collect { _rejected.tryEmit(Unit) } }
        active.start()
        try {
            val ended = active.state.first { it.phase == SessionPhase.ENDED }
            orphans = if (ended.end == SessionEnd.CLOSED) emptySet() else ended.held
            return ended.end
        } finally {
            withContext(NonCancellable) {
                session = null
                // Leaving the screen: cancel what is held and stop the helper before closing the session.
                active.close()
                mirror.cancel()
                trips.cancel()
                drops.cancel()
                val end = active.state.value.end
                link.finish(scope, clean = end == SessionEnd.CLOSED || end == SessionEnd.STOPPED)
                if (end == SessionEnd.CLOSED) orphans = emptySet()
            }
        }
    }

    private fun recentlySlept() = SystemClock.elapsedRealtime() - sleepSentAt < SLEEP_CUT_MS

    private companion object {
        const val TAG = "TVSlim/Remote"
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 1_000L

        /** A TV that cuts its network in standby does so within seconds of going to sleep. */
        const val SLEEP_CUT_MS = 60_000L
    }
}
