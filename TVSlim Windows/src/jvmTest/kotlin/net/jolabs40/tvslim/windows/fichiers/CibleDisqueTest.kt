package net.jolabs40.tvslim.windows.fichiers

import net.jolabs40.tvslim.windows.fichiers.CibleDisque.Companion.nomWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Local copy target: names Windows accepts, and never a partial file in place of the real one. */
class CibleDisqueTest {

    @get:Rule
    val dossier = TemporaryFolder()

    @Test
    fun `an Android name becomes a Windows name`() {
        assertEquals("Capture 12_30_05.png", nomWindows("Capture 12:30:05.png"))
        assertEquals("a_b_c_d_e_f_g_h", nomWindows("a<b>c\"d\\e|f?g*h"))
        assertEquals("fin", nomWindows("fin. ."))
        assertEquals("_", nomWindows("..."))
        assertEquals("_CON", nomWindows("CON"))
        assertEquals("_nul.txt", nomWindows("nul.txt"))
        assertEquals("_COM1.log", nomWindows("COM1.log"))
        assertEquals("CONSOLE.txt", nomWindows("CONSOLE.txt"))
        assertEquals("l'été 2024.mkv", nomWindows("l'été 2024.mkv"))
        assertEquals("a_b", nomWindows("a\tb"))
    }

    @Test
    fun `a file lands only when complete, with the TV's timestamp`() {
        val racine = dossier.newFolder("copies")
        val cible = CibleDisque(racine)
        cible.creerDossier("Films/Saison 1")

        cible.ecrire("Films/Saison 1/e01:final.mkv").use { ecriture ->
            ecriture.flux.write("image".toByteArray())
            assertFalse("Rien à sa place avant la validation", File(racine, "Films/Saison 1/e01_final.mkv").exists())
            ecriture.valider(1_700_000_000_000L)
        }

        val arrive = File(racine, "Films/Saison 1/e01_final.mkv")
        assertEquals("image", arrive.readText())
        assertEquals(1_700_000_000_000L, arrive.lastModified())
        assertTrue(cible.existe("Films/Saison 1/e01:final.mkv"))
        assertEquals(listOf("e01_final.mkv"), File(racine, "Films/Saison 1").list()!!.toList())
        assertEquals(File(racine, "Films").path, cible.decrire("Films"))
    }

    @Test
    fun `a stopped copy leaves nothing and does not overwrite the previous file`() {
        val racine = dossier.newFolder("copies")
        File(racine, "film.mkv").writeText("ancien")
        val cible = CibleDisque(racine)

        cible.ecrire("film.mkv").use { it.flux.write("nouv".toByteArray()) }

        assertEquals("ancien", File(racine, "film.mkv").readText())
        assertEquals(listOf("film.mkv"), racine.list()!!.toList())

        cible.ecrire("film.mkv").use { ecriture ->
            ecriture.flux.write("nouveau".toByteArray())
            ecriture.valider(0L)
        }
        assertEquals("nouveau", File(racine, "film.mkv").readText())
    }

    @Test(expected = IOException::class)
    fun `a file does not replace a folder`() {
        val racine = dossier.newFolder("copies")
        File(racine, "Films").mkdirs()
        CibleDisque(racine).ecrire("Films")
    }

    @Test(expected = IOException::class)
    fun `a folder is not created in place of a file`() {
        val racine = dossier.newFolder("copies")
        File(racine, "Films").writeText("x")
        CibleDisque(racine).creerDossier("Films/Saison 1")
    }
}
