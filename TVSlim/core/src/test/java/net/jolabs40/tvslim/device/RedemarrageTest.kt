package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurDirect
import net.jolabs40.tvslim.shell.ReponseDirecte
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

/** Le redémarrage : une seule commande, jamais rejouée, et une ligne au journal. */
class RedemarrageTest {

    private class Televiseur(private val panne: Boolean = false) : ExecuteurDirect {
        val commandes = mutableListOf<String>()

        override suspend fun executerUneFois(commande: String): ReponseDirecte {
            commandes += commande
            if (panne) throw IOException("Connection reset")
            return ReponseDirecte(code = null, sortie = "")
        }
    }

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    @Test
    fun `un redemarrage normal, une seule fois, consigne sans annulation`() = runTest {
        val tv = Televiseur()
        val journal = journal()

        Redemarrage(tv, journal).redemarrer()

        assertEquals(listOf("reboot"), tv.commandes)
        val ligne = journal.actions.value.single()
        assertEquals(TypeAction.COMMANDE, ligne.type)
        assertEquals("reboot", ligne.cible)
        assertEquals("", ligne.commandeAnnulation)
    }

    @Test
    fun `la connexion qui tombe en pleine commande est l'issue attendue`() = runTest {
        val tv = Televiseur(panne = true)
        val journal = journal()

        Redemarrage(tv, journal).redemarrer()

        assertEquals(1, tv.commandes.size)
        assertEquals(1, journal.actions.value.size)
    }
}
