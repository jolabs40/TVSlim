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
 *     ./gradlew jvmTest --tests "*SecondeSessionMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 */
class SecondSessionHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")

    @Test
    fun `the second session reads while the main one answers, and stays closed once closed`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", host != null)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connexion à $host : ${client.connection.value}", client.connect(host!!))
        try {
            val second = client.openSecond()
            assertNotNull("La seconde session doit s'ouvrir sans nouvelle autorisation", second)
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
                "dumpsys meminfo : ${System.currentTimeMillis() - start} ms, ${meminfo.output.length} caractères ; " +
                    "principale : ${latencies.size} réponses, au plus ${latencies.maxOrNull()} ms",
            )
            assertEquals(0, meminfo.code)
            assertTrue(latencies.size >= 3)
            assertTrue("La principale ne doit pas attendre la seconde : $latencies", latencies.max() < 1_500)

            // Closed during a command: the command returns at once and the session does not reopen.
            val longRunning = async(Dispatchers.IO) { second.execute("sleep 20; echo fin") }
            delay(500)
            val closing = System.currentTimeMillis()
            second.disconnect()
            val cut = longRunning.await()
            println("Coupée en ${System.currentTimeMillis() - closing} ms : $cut")
            assertTrue(cut.toString(), cut.code != 0)
            assertTrue(System.currentTimeMillis() - closing < 3_000)
            assertTrue("Fermée, elle ne se rouvre pas", second.execute("echo encore").code != 0)

            assertEquals("ok", client.execute("echo ok").output)
        } finally {
            client.disconnect()
        }
    }
}
