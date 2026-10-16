package net.jolabs40.tvslim.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.applications.ApplicationsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ControlSessionTest {

    /** The helper's side of the link: records what the phone writes and answers like the helper would. */
    private class FakeHelper(
        private val scope: CoroutineScope,
        var autoAck: Boolean = true,
        var ackDelayMs: Long = 0,
        var writeDelayMs: Long = 0,
    ) : ControlChannel {
        val written = mutableListOf<String>()
        val incoming = Channel<String?>(Channel.UNLIMITED)
        var broken = false
        var closed = false

        override suspend fun write(line: String) {
            if (broken) throw IOException("Software caused connection abort")
            if (writeDelayMs > 0) delay(writeDelayMs)
            written += line
            val id = line.split('\t').getOrNull(1)?.toLongOrNull() ?: return
            if (autoAck) scope.launch {
                delay(ackDelayMs)
                incoming.send("K\t$id\tok")
            }
        }

        override suspend fun read(): String? = incoming.receive()

        override fun close() {
            closed = true
            incoming.close()
        }

        fun ready() {
            incoming.trySend("TVSLIM_AIDE ${ApplicationsReader.HELPER_VERSION}")
            incoming.trySend("R\t34\tglobal\t400\t50\t1\t1")
        }

        fun orders(letter: String) = written.filter { it.startsWith("$letter\t") }
    }

    private fun TestScope.session(helper: FakeHelper, orphans: Set<Int> = emptySet()) =
        ControlSession(helper, backgroundScope, { testScheduler.currentTime }, orphans).also { it.start() }

    private fun TestScope.readySession(helper: FakeHelper = FakeHelper(backgroundScope)): Pair<FakeHelper, ControlSession> {
        val session = session(helper)
        helper.ready()
        runCurrent()
        assertEquals(SessionPhase.READY, session.state.value.phase)
        return helper to session
    }

    @Test
    fun `presses before the helper is ready are refused, not queued`() = runTest {
        val helper = FakeHelper(backgroundScope)
        val session = session(helper)
        runCurrent()

        assertFalse(session.press(RemoteKey.RIGHT))
        assertFalse(session.tap(RemoteKey.PLAY_PAUSE))
        helper.ready()
        advanceTimeBy(500)

        assertTrue(helper.written.isEmpty())
        assertTrue(session.press(RemoteKey.RIGHT))
        runCurrent()
        assertEquals(1, helper.orders("D").size)
        assertTrue(helper.orders("D").single().endsWith("\t22"))
    }

    @Test
    fun `a broken link is never replayed, and the next session cancels what was held`() = runTest {
        val (helper, session) = readySession()
        session.press(RemoteKey.RIGHT)
        runCurrent()
        helper.broken = true
        session.press(RemoteKey.CENTER)
        runCurrent()

        assertEquals(SessionPhase.ENDED, session.state.value.phase)
        assertEquals(SessionEnd.BROKEN, session.state.value.end)
        val orphans = session.state.value.held
        assertEquals(setOf(RemoteKey.RIGHT.code, RemoteKey.CENTER.code), orphans)

        val next = FakeHelper(backgroundScope)
        session(next, orphans)
        next.ready()
        runCurrent()
        assertTrue("nothing pressed again", next.orders("D").isEmpty())
        assertEquals(listOf("22", "23"), next.orders("C").map { it.substringAfterLast('\t') })
    }

    @Test
    fun `pings every second, every 100 ms while a key is held`() = runTest {
        val (helper, session) = readySession()
        advanceTimeBy(2_010)
        assertEquals(2, helper.orders("P").size)

        session.press(RemoteKey.DOWN)
        advanceTimeBy(1_000)
        assertTrue(helper.orders("P").size >= 2 + 9)

        session.release(RemoteKey.DOWN)
        val before = helper.orders("P").size
        advanceTimeBy(1_000)
        assertTrue(helper.orders("P").size <= before + 1)
        assertEquals(1, helper.orders("U").size)
    }

    @Test
    fun `unanswered orders end the session and keep the held key for the next one`() = runTest {
        val (helper, session) = readySession(FakeHelper(backgroundScope, autoAck = false))
        session.press(RemoteKey.RIGHT)
        advanceTimeBy(ControlSession.ACK_TIMEOUT_MS + 200)

        assertEquals(SessionEnd.SILENT, session.state.value.end)
        assertEquals(setOf(RemoteKey.RIGHT.code), session.state.value.held)
        assertTrue(helper.closed)
        assertFalse(session.press(RemoteKey.LEFT))
    }

    @Test
    fun `closing cancels held keys, then asks the helper to stop`() = runTest {
        val (helper, session) = readySession()
        session.press(RemoteKey.RIGHT)
        runCurrent()
        launch { session.close() }
        runCurrent()
        helper.incoming.trySend("B\tquit")
        runCurrent()

        assertEquals(listOf("C", "Q"), helper.written.takeLast(2).map { it.substringBefore('\t') })
        assertEquals(SessionEnd.CLOSED, session.state.value.end)
        assertTrue(session.state.value.held.isEmpty())
        assertTrue(helper.closed)
    }

    @Test
    fun `a press stuck behind a slow write is dropped, a release is not`() = runTest {
        val (helper, session) = readySession()
        val rejected = mutableListOf<AckStatus>()
        backgroundScope.launch { session.rejected.collect { rejected += it } }
        helper.writeDelayMs = 400
        session.press(RemoteKey.RIGHT)
        session.press(RemoteKey.LEFT)
        session.release(RemoteKey.LEFT)
        advanceTimeBy(2_000)

        assertEquals(listOf("22"), helper.orders("D").map { it.substringAfterLast('\t') })
        assertEquals(listOf("21"), helper.orders("U").map { it.substringAfterLast('\t') })
        assertTrue(AckStatus.LATE in rejected)
    }

    @Test
    fun `a key the helper released by itself is no longer held`() = runTest {
        val (helper, session) = readySession()
        session.press(RemoteKey.RIGHT)
        runCurrent()
        helper.incoming.trySend("L\t22")
        runCurrent()
        assertTrue(session.state.value.held.isEmpty())
    }

    @Test
    fun `another helper version is rejected`() = runTest {
        val helper = FakeHelper(backgroundScope)
        val session = session(helper)
        helper.incoming.trySend("TVSLIM_AIDE ${ApplicationsReader.HELPER_VERSION - 1}")
        runCurrent()
        assertEquals(SessionEnd.REJECTED, session.state.value.end)
    }

    @Test
    fun `a helper that never gets ready times out`() = runTest {
        val session = session(FakeHelper(backgroundScope))
        advanceTimeBy(ControlSession.START_TIMEOUT_MS + 100)
        assertEquals(SessionEnd.SILENT, session.state.value.end)
    }

    @Test
    fun `round trips give the latency, refusals are reported`() = runTest {
        val (helper, session) = readySession(FakeHelper(backgroundScope, ackDelayMs = 20))
        val rejected = mutableListOf<AckStatus>()
        backgroundScope.launch { session.rejected.collect { rejected += it } }
        session.tap(RemoteKey.PLAY_PAUSE)
        advanceTimeBy(100)
        assertEquals(20L, session.state.value.latencyMs)

        helper.autoAck = false
        session.tap(RemoteKey.MUTE)
        runCurrent()
        val id = helper.orders("T").last().split('\t')[1]
        helper.incoming.trySend("K\t$id\tlate")
        runCurrent()
        assertEquals(listOf(AckStatus.LATE), rejected)
    }

    @Test
    fun `screen state follows the helper`() = runTest {
        val (helper, session) = readySession()
        assertEquals(true, session.state.value.screenOn)
        helper.incoming.trySend("V\t0")
        runCurrent()
        assertEquals(false, session.state.value.screenOn)
    }

    private fun FakeHelper.answer(id: String, status: String, payload: String) {
        incoming.trySend("Y\t$id\t$status\t" + java.util.Base64.getEncoder().encodeToString(payload.toByteArray()))
    }

    @Test
    fun `a provider call returns what the TV answered`() = runTest {
        val (helper, session) = readySession()
        val outcome = async { session.call("net.jolabs40.startlight.remote", "etat") }
        runCurrent()
        val order = helper.orders("A").single().split('\t')
        assertEquals("net.jolabs40.startlight.remote", order[3])
        helper.answer(order[1], "ok", """{"json":"{}"}""")
        runCurrent()
        assertEquals(CallOutcome.Answered("""{"json":"{}"}"""), outcome.await())
    }

    @Test
    fun `a call that failed on the TV says why, and is not sent again`() = runTest {
        val (helper, session) = readySession()
        val outcome = async { session.call("absent.remote", "etat") }
        runCurrent()
        helper.answer(helper.orders("A").single().split('\t')[1], "error", "no provider absent.remote")
        advanceTimeBy(2_000)
        assertEquals(CallOutcome.Failed("no provider absent.remote"), outcome.await())
        assertEquals(1, helper.orders("A").size)
    }

    @Test
    fun `a call the helper found late is reported late`() = runTest {
        val (helper, session) = readySession(FakeHelper(backgroundScope, autoAck = false))
        val outcome = async { session.call("net.jolabs40.startlight.remote", "fond_suivant") }
        runCurrent()
        helper.incoming.trySend("K\t" + helper.orders("A").single().split('\t')[1] + "\tlate")
        runCurrent()
        assertEquals(CallOutcome.NotSent(CallFailure.LATE), outcome.await())
    }

    @Test
    fun `a call needs a ready link and identifiers`() = runTest {
        val helper = FakeHelper(backgroundScope)
        val session = session(helper)
        assertEquals(CallOutcome.NotSent(CallFailure.NOT_READY), session.call("a.remote", "etat"))
        helper.ready()
        runCurrent()
        assertEquals(CallOutcome.NotSent(CallFailure.NOT_READY), session.call("a.remote\tQ", "etat"))
        assertEquals(CallOutcome.NotSent(CallFailure.NOT_READY), session.call("a.remote", "etat; reboot"))
        assertTrue(helper.orders("A").isEmpty())
    }

    @Test
    fun `a call pending when the link drops ends without a retry`() = runTest {
        val (helper, session) = readySession()
        val outcome = async { session.call("net.jolabs40.startlight.remote", "fond_suivant") }
        runCurrent()
        helper.incoming.trySend(null)
        runCurrent()
        assertEquals(CallOutcome.NotSent(CallFailure.ENDED), outcome.await())
        assertEquals(1, helper.orders("A").size)
    }

    @Test
    fun `a call without an answer times out, its fate unknown`() = runTest {
        val (_, session) = readySession()
        val outcome = async { session.call("net.jolabs40.startlight.remote", "etat") }
        advanceTimeBy(ControlSession.CALL_TIMEOUT_MS + 100)
        assertEquals(CallOutcome.NotSent(CallFailure.TIMEOUT), outcome.await())
    }

    @Test
    fun `text goes out as typed`() = runTest {
        val (helper, session) = readySession()
        assertFalse(session.text(""))
        assertTrue(session.text("Café"))
        runCurrent()
        assertEquals(1, helper.orders("X").size)
    }
}
