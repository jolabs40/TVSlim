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

/** Free-form ADB command: what is sent from the input, sent only once, and what the journal keeps. */
class ConsoleAdbTest {

    /** Fake TV that records commands and returns the given response. */
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
    fun `a bare command is sent as is`() {
        assertEquals(prete("pm list packages -d"), ConsoleAdb.lire("  pm list packages -d "))
        assertEquals(prete("adbd --version"), ConsoleAdb.lire("adbd --version"))
    }

    @Test
    fun `the adb shell wrapper is stripped, device and quotes included`() {
        assertEquals(prete("pm list packages -d"), ConsoleAdb.lire("adb shell pm list packages -d"))
        assertEquals(
            prete("getprop ro.product.model"),
            ConsoleAdb.lire("adb -s 192.168.2.135:5555 shell getprop ro.product.model"),
        )
        assertEquals(
            prete("dumpsys package net.jolabs40.tvslim | grep version"),
            ConsoleAdb.lire("adb -d shell \"dumpsys package net.jolabs40.tvslim | grep version\""),
        )
        // Quotes around only part of the command stay, for the TV shell to handle.
        assertEquals(
            prete("settings put global nom 'a b'"),
            ConsoleAdb.lire("adb shell settings put global nom 'a b'"),
        )
    }

    @Test
    fun `other adb commands and empty input are not sent`() {
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
    fun `a command is sent once and journaled with no undo`() = runTest {
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
    fun `a command cut off keeps the output written so far and is not replayed`() = runTest {
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
    fun `a nonzero exit code is a failure, and the output gives the reason`() = runTest {
        val tv = Televiseur { ReponseDirecte(255, "Error: unknown command 'lister'") }
        val carnet = journal()

        val echange = ConsoleAdb(tv) { carnet }.envoyer("pm lister")

        assertFalse(echange.reussie)
        assertEquals("Error: unknown command 'lister'", carnet.actions.value.single().message)
    }

    @Test
    fun `oversized output is truncated for display`() = runTest {
        val tv = Televiseur { ReponseDirecte(0, "x".repeat(ConsoleAdb.SORTIE_MAX + 10)) }

        val echange = ConsoleAdb(tv) { null }.envoyer("dumpsys")

        assertEquals(ConsoleAdb.SORTIE_MAX, echange.sortie.length)
        assertEquals(ConsoleAdb.SORTIE_MAX + 10, echange.longueurRecue)
        assertTrue(echange.tronquee)
    }
}
