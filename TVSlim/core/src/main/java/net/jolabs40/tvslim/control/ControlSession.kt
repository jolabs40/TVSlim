package net.jolabs40.tvslim.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.applications.ApplicationsReader
import java.util.concurrent.atomic.AtomicLong

/** A line link to the helper; the phone's is a shell stream of a dedicated ADB session. */
interface ControlChannel {
    /** Writes one line; throws once the link is broken. */
    suspend fun write(line: String)

    /** The next line from the helper, or null once it has stopped. */
    suspend fun read(): String?

    /** Closes the link, which also ends a pending [read]. */
    fun close()
}

enum class SessionPhase { STARTING, READY, ENDED }

enum class SessionEnd {
    /** Closed by the phone. */
    CLOSED,

    /** The helper stopped, or its stream ended. */
    STOPPED,

    /** Orders went unanswered: the TV or the Wi-Fi is gone. */
    SILENT,

    /** Writing failed. */
    BROKEN,

    /** The helper is not the expected version. */
    REJECTED,
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.STARTING,
    val ready: HelperReady? = null,
    val screenOn: Boolean? = null,
    /** Median of the recent round trips, in ms. */
    val latencyMs: Long? = null,
    /** Key codes held down. After an abnormal end, those the helper may not have released. */
    val held: Set<Int> = emptySet(),
    val end: SessionEnd? = null,
)

/**
 * One remote-control session with a running helper.
 *
 * An order is sent once and never again: if the link breaks, it is lost, and the next session starts empty. Presses
 * while the link is not ready are refused rather than queued, so nothing fires seconds later. Releases always go
 * out. While a key is held the helper hears from the phone every [HELD_PING_MS], and releases it by itself when
 * the phone falls silent.
 */
class ControlSession(
    private val channel: ControlChannel,
    parent: CoroutineScope,
    private val clock: () -> Long,
    /** Keys a broken session left held: canceled first, in case the helper that held them was killed. */
    private val orphans: Set<Int> = emptySet(),
) {

    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _rejected = MutableSharedFlow<AckStatus>(extraBufferCapacity = 16)

    /** Orders the helper dropped or refused, and presses refused here: worth a different vibration. */
    val rejected: SharedFlow<AckStatus> = _rejected.asSharedFlow()

    private val _roundTrips = MutableSharedFlow<Long>(extraBufferCapacity = 64)
    val roundTrips: SharedFlow<Long> = _roundTrips.asSharedFlow()

    private class Order(val id: Long, val createdAt: Long, val line: String, val expires: Boolean)

    private val outgoing = Channel<Order>(Channel.UNLIMITED)
    private val ids = AtomicLong(0)

    /** Orders written and not yet answered, by id, with the time they were written. */
    private val inFlight = LinkedHashMap<Long, Long>()
    private val recentTrips = ArrayDeque<Long>()
    private var lastPing = 0L
    private var startedAt = 0L

    @Volatile
    private var closing = false

    fun start() {
        startedAt = clock()
        scope.launch { readLoop() }
        scope.launch { writeLoop() }
        scope.launch { tickLoop() }
    }

    /** False when the press cannot go out now; the screen then vibrates differently. */
    fun press(key: RemoteKey): Boolean {
        if (!key.holdable) return tap(key)
        if (!ready() || key.code in _state.value.held) return refuse()
        _state.update { it.copy(held = it.held + key.code) }
        return enqueue(expires = true) { id, at -> ControlProtocol.press(id, at, key.code) }
    }

    fun release(key: RemoteKey) {
        if (key.code !in _state.value.held) return
        _state.update { it.copy(held = it.held - key.code) }
        enqueue(expires = false) { id, at -> ControlProtocol.release(id, at, key.code) }
    }

    /** A release that must not act: the finger slid off the button, or the screen went away mid-press. */
    fun cancel(key: RemoteKey) {
        if (key.code !in _state.value.held) return
        _state.update { it.copy(held = it.held - key.code) }
        enqueue(expires = false) { id, at -> ControlProtocol.cancel(id, at, key.code) }
    }

    fun tap(key: RemoteKey): Boolean {
        if (!ready()) return refuse()
        return enqueue(expires = true) { id, at -> ControlProtocol.tap(id, at, key.code) }
    }

    fun wake(): Boolean = if (ready()) enqueue(expires = true) { id, at -> ControlProtocol.wake(id, at) } else refuse()

    fun sleep(): Boolean = if (ready()) enqueue(expires = true) { id, at -> ControlProtocol.sleep(id, at) } else refuse()

    /** Typed on purpose, so it does not expire like a key. */
    fun text(text: String): Boolean {
        if (text.isEmpty()) return false
        return if (ready()) enqueue(expires = false) { id, at -> ControlProtocol.text(id, at, text) } else refuse()
    }

    /** Cancels what is held, asks the helper to stop, then closes the link. */
    suspend fun close() {
        if (_state.value.phase == SessionPhase.ENDED) return
        closing = true
        for (code in _state.value.held) enqueue(expires = false) { id, at -> ControlProtocol.cancel(id, at, code) }
        _state.update { it.copy(held = emptySet()) }
        outgoing.trySend(Order(-1, clock(), ControlProtocol.QUIT, expires = false))
        outgoing.close()
        // The helper answers Q with its last line; past that wait, closing the stream stops it anyway.
        withTimeoutOrNull(CLOSE_WAIT_MS) { job.children.forEach { it.join() } }
        end(SessionEnd.CLOSED)
    }

    private fun ready() = _state.value.phase == SessionPhase.READY

    private fun refuse(): Boolean {
        _rejected.tryEmit(AckStatus.REFUSED)
        return false
    }

    private fun enqueue(expires: Boolean, line: (id: Long, at: Long) -> String): Boolean {
        val id = ids.incrementAndGet()
        val now = clock()
        return outgoing.trySend(Order(id, now, line(id, now), expires)).isSuccess
    }

    private suspend fun writeLoop() {
        for (order in outgoing) {
            val now = clock()
            // Stuck behind a slow write: a press that old would surprise more than it helps.
            if (order.expires && now - order.createdAt > LOCAL_EXPIRY_MS) {
                _rejected.tryEmit(AckStatus.LATE)
                continue
            }
            try {
                channel.write(order.line)
            } catch (error: Exception) {
                end(SessionEnd.BROKEN)
                return
            }
            if (order.id > 0) synchronized(inFlight) { inFlight[order.id] = now }
        }
    }

    private suspend fun readLoop() {
        while (true) {
            val line = try {
                channel.read()
            } catch (error: Exception) {
                end(SessionEnd.BROKEN)
                return
            } ?: run {
                end(SessionEnd.STOPPED)
                return
            }
            when (val parsed = ControlProtocol.parse(line)) {
                is HelperLine.Version -> if (parsed.version != ApplicationsReader.HELPER_VERSION) {
                    end(SessionEnd.REJECTED)
                    return
                }

                is HelperLine.Ready -> {
                    _state.update {
                        it.copy(phase = SessionPhase.READY, ready = parsed.ready, screenOn = parsed.ready.screenOn)
                    }
                    for (code in orphans) enqueue(expires = false) { id, at -> ControlProtocol.cancel(id, at, code) }
                }

                is HelperLine.Ack -> acknowledge(parsed)
                is HelperLine.Released -> _state.update { it.copy(held = it.held - parsed.code) }
                is HelperLine.Screen -> _state.update { it.copy(screenOn = parsed.on) }
                is HelperLine.Bye -> {
                    end(SessionEnd.STOPPED)
                    return
                }

                is HelperLine.Error, null -> Unit
            }
        }
    }

    private fun acknowledge(ack: HelperLine.Ack) {
        val writtenAt = synchronized(inFlight) { inFlight.remove(ack.id) } ?: return
        val trip = clock() - writtenAt
        _roundTrips.tryEmit(trip)
        recentTrips.addLast(trip)
        if (recentTrips.size > LATENCY_WINDOW) recentTrips.removeFirst()
        _state.update { it.copy(latencyMs = recentTrips.sorted()[recentTrips.size / 2]) }
        if (ack.status == AckStatus.LATE || ack.status == AckStatus.REFUSED) _rejected.tryEmit(ack.status)
    }

    /** Pings (every second, or every [HELD_PING_MS] while a key is held) and the watch on unanswered orders. */
    private suspend fun tickLoop() {
        while (_state.value.phase != SessionPhase.ENDED) {
            delay(TICK_MS)
            val now = clock()
            val state = _state.value
            if (state.phase == SessionPhase.STARTING && now - startedAt > START_TIMEOUT_MS) {
                end(SessionEnd.SILENT)
                return
            }
            val oldest = synchronized(inFlight) { inFlight.values.minOrNull() }
            if (oldest != null && now - oldest > ACK_TIMEOUT_MS) {
                end(SessionEnd.SILENT)
                return
            }
            if (state.phase == SessionPhase.READY) {
                val interval = if (state.held.isEmpty()) IDLE_PING_MS else HELD_PING_MS
                if (now - lastPing >= interval) {
                    lastPing = now
                    enqueue(expires = false) { id, at -> ControlProtocol.ping(id, at) }
                }
            }
        }
    }

    private fun end(reason: SessionEnd) {
        val before = _state.value
        if (before.phase == SessionPhase.ENDED) return
        _state.value = before.copy(phase = SessionPhase.ENDED, end = if (closing) SessionEnd.CLOSED else reason)
        outgoing.close()
        channel.close()
        job.cancel()
    }

    companion object {
        const val TICK_MS = 50L
        const val IDLE_PING_MS = 1_000L
        const val HELD_PING_MS = 100L

        /** Several pings unanswered: the helper itself gives up on the phone after 500 ms of silence while a key is held. */
        const val ACK_TIMEOUT_MS = 1_500L

        /** Push, launch and the helper's ready line. */
        const val START_TIMEOUT_MS = 10_000L

        const val LOCAL_EXPIRY_MS = 300L
        const val CLOSE_WAIT_MS = 1_000L
        const val LATENCY_WINDOW = 20
    }
}
