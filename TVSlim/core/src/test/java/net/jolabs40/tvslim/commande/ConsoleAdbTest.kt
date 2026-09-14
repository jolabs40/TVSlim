package net.jolabs40.tvslim.commande

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurDirect
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.shell.ReponseDirecte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** La commande libre : ce qui part de la saisie, une seule fois, et ce que le journal en garde. */
class ConsoleAdbTest {

    /** Un téléviseur bouchon : il retient les commandes et répond ce qu'on lui dit. */
    private class Televiseur(
        private val reponse: (String) -> ReponseDirecte = { ReponseDirecte(0, "ok") },
    ) : ExecuteurDirect {
        val commandes = mutableListOf<String>()

        override suspend fun executerUneFois(commande: String): ReponseDirecte {
            commandes += commande
            return reponse(commande)
        }
    }

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun prete(commande: String) = SaisieCommande.Prete(commande)

    private fun refusee(refus: RefusCommande) = SaisieCommande.Refusee(refus)

    @Test
    fun `une commande seule part telle quelle`() {
        assertEquals(prete("pm list packages -d"), ConsoleAdb.lire("  pm list packages -d "))
        assertEquals(prete("adbd --version"), ConsoleAdb.lire("adbd --version"))
    }

    @Test
    fun `l'enveloppe d'adb shell est retiree, appareil et guillemets compris`() {
        assertEquals(prete("pm list packages -d"), ConsoleAdb.lire("adb shell pm list packages -d"))
        assertEquals(
            prete("getprop ro.product.model"),
            ConsoleAdb.lire("adb -s 192.168.2.135:5555 shell getprop ro.product.model"),
        )
        assertEquals(
            prete("dumpsys package net.jolabs40.tvslim | grep version"),
            ConsoleAdb.lire("adb -d shell \"dumpsys package net.jolabs40.tvslim | grep version\""),
        )
        // Des guillemets qui n'entourent qu'une partie restent : le shell du téléviseur les lira.
        assertEquals(
            prete("settings put global nom 'a b'"),
            ConsoleAdb.lire("adb shell settings put global nom 'a b'"),
        )
    }

    @Test
    fun `les autres commandes d'adb et une saisie vide ne partent pas`() {
        assertEquals(refusee(RefusCommande.PAS_SHELL), ConsoleAdb.lire("adb install HippieTV.apk"))
        assertEquals(refusee(RefusCommande.PAS_SHELL), ConsoleAdb.lire("adb reboot"))
        assertEquals(refusee(RefusCommande.VIDE), ConsoleAdb.lire("adb shell"))
        assertEquals(refusee(RefusCommande.VIDE), ConsoleAdb.lire("   "))
        assertEquals(
            refusee(RefusCommande.TROP_LONGUE),
            ConsoleAdb.lire("echo " + "x".repeat(ConsoleAdb.LONGUEUR_MAX)),
        )
    }

    @Test
    fun `une commande part une seule fois et se consigne sans annulation`() = runTest {
        val tv = Televiseur { ReponseDirecte(0, "package:com.tcl.gallery") }
        val carnet = journal()

        val echange = ConsoleAdb(tv) { carnet }.envoyer("pm list packages -d")

        assertEquals(listOf("pm list packages -d"), tv.commandes)
        assertTrue(echange.reussie)
        assertEquals("package:com.tcl.gallery", echange.sortie)
        with(carnet.actions.value.single()) {
            assertEquals(TypeAction.COMMANDE, type)
            assertEquals("pm list packages -d", cible)
            assertEquals("", commandeAnnulation)
            assertTrue(reussi)
        }
    }

    @Test
    fun `une commande coupee garde ce qu'elle a ecrit, et n'est pas rejouee`() = runTest {
        val tv = Televiseur { ReponseDirecte(null, "début\n", Interruption.DELAI, "délai dépassé") }
        val carnet = journal()

        val echange = ConsoleAdb(tv) { carnet }.envoyer("logcat")

        assertEquals(1, tv.commandes.size)
        assertFalse(echange.reussie)
        assertEquals("début\n", echange.sortie)
        with(carnet.actions.value.single()) {
            assertFalse(reussi)
            assertEquals("Coupée par le délai maximal.", message)
        }
    }

    @Test
    fun `un code non nul est un echec, et sa sortie en dit le motif`() = runTest {
        val tv = Televiseur { ReponseDirecte(255, "Error: unknown command 'lister'") }
        val carnet = journal()

        val echange = ConsoleAdb(tv) { carnet }.envoyer("pm lister")

        assertFalse(echange.reussie)
        assertEquals("Error: unknown command 'lister'", carnet.actions.value.single().message)
    }

    @Test
    fun `une sortie demesuree est tronquee pour l'affichage`() = runTest {
        val tv = Televiseur { ReponseDirecte(0, "x".repeat(ConsoleAdb.SORTIE_MAX + 10)) }

        val echange = ConsoleAdb(tv) { null }.envoyer("dumpsys")

        assertEquals(ConsoleAdb.SORTIE_MAX, echange.sortie.length)
        assertEquals(ConsoleAdb.SORTIE_MAX + 10, echange.longueurRecue)
        assertTrue(echange.tronquee)
    }
}
