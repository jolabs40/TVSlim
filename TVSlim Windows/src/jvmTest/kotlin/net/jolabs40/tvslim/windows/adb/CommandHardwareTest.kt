package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.windows.Locations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Free-form command on a real TV: one succeeds, one fails, one never ends and is cut by the timeout with its
 * output kept, then the session reopens by itself. Read-only. Opt-in, since the cut command takes 30 s:
 *
 *     ./gradlew jvmTest --tests "*CommandHardwareTest*" '-Phardware=192.168.2.135' --rerun
 */
class CommandHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")

    @Test
    fun `one command succeeds, one fails, one is cut off, and the next one still runs`() = runBlocking<Unit> {
        assumeTrue("-Phardware=<address> to try on a real TV", host != null)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        val connected = client.connect(host!!)
        assertTrue("Connection to $host: ${client.connection.value}", connected)
        try {
            val console = AdbConsole(client) { null }

            val model = console.send("getprop ro.product.model")
            println("Model: $model")
            assertEquals(0, model.code)
            assertTrue(model.output.isNotBlank())

            val failure = console.send("ls /doesnotexist")
            println("Failure: $failure")
            assertTrue(failure.toString(), failure.code != null && failure.code != 0)

            val start = System.currentTimeMillis()
            val cut = console.send("echo start; sleep 60; echo end")
            println("Cut after ${System.currentTimeMillis() - start} ms: $cut")
            assertEquals(Interruption.TIMEOUT, cut.interruption)
            assertTrue(cut.output, cut.output.contains("start"))

            val after = console.send("echo after")
            println("After: $after")
            assertEquals("after", after.output)
        } finally {
            client.disconnect()
        }
    }
}
