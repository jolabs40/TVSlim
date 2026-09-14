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
 * La commande libre sur un vrai téléviseur : une commande qui réussit, une qui échoue, une qui ne finit
 * pas et que le délai coupe en gardant ce qu'elle a écrit, puis la session qui se rouvre d'elle-même.
 * Rien n'est écrit sur le téléviseur. Ne tourne que sur demande — la commande coupée prend trente
 * secondes :
 *
 *     ./gradlew jvmTest --tests "*CommandeMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 */
class CommandeMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")

    @Test
    fun `une commande reussit, une echoue, une est coupee, et la suivante repart`() = runBlocking<Unit> {
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
