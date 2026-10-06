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
 * La seconde session du préchargement sur un vrai téléviseur : `dumpsys meminfo` y tourne pendant que la principale
 * répond sans l'attendre ; la fermer interrompt une commande en cours, et elle ne se rouvre pas d'elle-même. Rien
 * n'est écrit sur le téléviseur :
 *
 *     ./gradlew jvmTest --tests "*SecondeSessionMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 */
class SecondeSessionMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")

    @Test
    fun `la seconde session lit pendant que la principale repond, et se ferme sans retour`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", hote != null)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        assertTrue("Connexion à $hote : ${client.connexion.value}", client.connecter(hote!!))
        try {
            val seconde = client.ouvrirSeconde()
            assertNotNull("La seconde session doit s'ouvrir sans nouvelle autorisation", seconde)
            seconde!!

            // Une lecture lourde sur la seconde, des allers-retours sur la principale pendant ce temps.
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

            // Fermée pendant une commande : celle-ci rend la main aussitôt, et la session ne se rouvre pas.
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
