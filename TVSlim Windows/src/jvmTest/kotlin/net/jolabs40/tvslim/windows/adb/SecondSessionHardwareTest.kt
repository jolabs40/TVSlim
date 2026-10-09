package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.windows.Locations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The preload's second session on a real TV: `dumpsys meminfo` runs on it while the main session keeps answering;
 * closing it interrupts a running command, and it does not reopen by itself. Read-only:
 *
 *     ./gradlew jvmTest --tests "*SecondSessionHardwareTest*" '-Phardware=192.168.2.135' --rerun
 */
class SecondSessionHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")

    @Test
    fun `the second session reads while the main one answers, and stays closed once closed`() = runBlocking<Unit> {
        assumeTrue("-Phardware=<address> to try on a real TV", host != null)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connection to $host: ${client.connection.value}", client.connect(host!!))
        try {
            val second = client.openSecond()
            assertNotNull("The second session must open without a new authorization", second)
            second!!

            // A heavy read on the second session, round trips on the main one meanwhile.
            val start = System.currentTimeMillis()
            val heavy = async(Dispatchers.IO) { second.execute("dumpsys meminfo") }
            val latencies = mutableListOf<Long>()
            while (!heavy.isCompleted) {
                val t = System.currentTimeMillis()
                assertEquals("ok", client.execute("echo ok").output)
                latencies += System.currentTimeMillis() - t
                delay(100)
            }
            val meminfo = heavy.await()
            println(
                "dumpsys meminfo: ${System.currentTimeMillis() - start} ms, ${meminfo.output.length} characters; " +
                    "main: ${latencies.size} answers, at most ${latencies.maxOrNull()} ms",
            )
            assertEquals(0, meminfo.code)
            assertTrue(latencies.size >= 3)
            assertTrue("The main session must not wait for the second: $latencies", latencies.max() < 1_500)

            // Closed during a command: the command returns at once and the session does not reopen.
            val longRunning = async(Dispatchers.IO) { second.execute("sleep 20; echo end") }
            delay(500)
            val closing = System.currentTimeMillis()
            second.disconnect()
            val cut = longRunning.await()
            println("Cut after ${System.currentTimeMillis() - closing} ms: $cut")
            assertTrue(cut.toString(), cut.code != 0)
            assertTrue(System.currentTimeMillis() - closing < 3_000)
            assertTrue("Once closed, it does not reopen", second.execute("echo again").code != 0)

            assertEquals("ok", client.execute("echo ok").output)
        } finally {
            client.disconnect()
        }
    }
}
