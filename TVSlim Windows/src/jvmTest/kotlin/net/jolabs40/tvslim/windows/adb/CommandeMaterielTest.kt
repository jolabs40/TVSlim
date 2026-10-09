package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.windows.Emplacements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Free-form command on a real TV: one succeeds, one fails, one never ends and is cut by the timeout with its
 * output kept, then the session reopens by itself. Read-only. Opt-in, since the cut command takes 30 s:
 *
 *     ./gradlew jvmTest --tests "*CommandeMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 */
class CommandeMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")

    @Test
    fun `one command succeeds, one fails, one is cut off, and the next one still runs`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", hote != null)
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        val connecte = client.connecter(hote!!)
        assertTrue("Connexion à $hote : ${client.connexion.value}", connecte)
        try {
            val console = ConsoleAdb(client) { null }

            val modele = console.envoyer("getprop ro.product.model")
            println("Modèle : $modele")
            assertEquals(0, modele.code)
            assertTrue(modele.sortie.isNotBlank())

            val echec = console.envoyer("ls /nexistepas")
            println("Échec : $echec")
            assertTrue(echec.toString(), echec.code != null && echec.code != 0)

            val debut = System.currentTimeMillis()
            val coupee = console.envoyer("echo debut; sleep 60; echo fin")
            println("Coupée en ${System.currentTimeMillis() - debut} ms : $coupee")
            assertEquals(Interruption.DELAI, coupee.interruption)
            assertTrue(coupee.sortie, coupee.sortie.contains("debut"))

            val apres = console.envoyer("echo apres")
            println("Après : $apres")
            assertEquals("apres", apres.sortie)
        } finally {
            client.deconnecter()
        }
    }
}
