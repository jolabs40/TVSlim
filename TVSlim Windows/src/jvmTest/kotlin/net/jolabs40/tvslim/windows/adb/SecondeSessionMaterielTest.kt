package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.windows.Emplacements
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
class SecondeSessionMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")

    @Test
    fun `the second session reads while the main one answers, and stays closed once closed`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", hote != null)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        try {
            val seconde = client.ouvrirSeconde()
            assertNotNull("La seconde session doit s'ouvrir sans nouvelle autorisation", seconde)
            seconde!!

            // A heavy read on the second session, round trips on the main one meanwhile.
            val debut = System.currentTimeMillis()
            val lourde = async(Dispatchers.IO) { seconde.executer("dumpsys meminfo") }
            val latences = mutableListOf<Long>()
            while (!lourde.isCompleted) {
                val t = System.currentTimeMillis()
                assertEquals("ok", client.executer("echo ok").sortie)
                latences += System.currentTimeMillis() - t
                delay(100)
            }
            val meminfo = lourde.await()
            println(
                "dumpsys meminfo : ${System.currentTimeMillis() - debut} ms, ${meminfo.sortie.length} caractères ; " +
                    "principale : ${latences.size} réponses, au plus ${latences.maxOrNull()} ms",
            )
            assertEquals(0, meminfo.code)
            assertTrue(latences.size >= 3)
            assertTrue("La principale ne doit pas attendre la seconde : $latences", latences.max() < 1_500)

            // Closed during a command: the command returns at once and the session does not reopen.
            val longue = async(Dispatchers.IO) { seconde.executer("sleep 20; echo fin") }
            delay(500)
            val fermeture = System.currentTimeMillis()
            seconde.deconnecter()
            val coupee = longue.await()
            println("Coupée en ${System.currentTimeMillis() - fermeture} ms : $coupee")
            assertTrue(coupee.toString(), coupee.code != 0)
            assertTrue(System.currentTimeMillis() - fermeture < 3_000)
            assertTrue("Fermée, elle ne se rouvre pas", seconde.executer("echo encore").code != 0)

            assertEquals("ok", client.executer("echo ok").sortie)
        } finally {
            client.deconnecter()
        }
    }
}
