package net.jolabs40.tvslim.windows.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreferencesWindowsTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private fun fichier() = File(dossier.root, "preferences.json")

    @Test
    fun `sans fichier, les valeurs par defaut s'appliquent`() = runTest {
        val lues = PreferencesWindows(fichier()).lire()

        assertEquals("", lues.dernierHote)
        assertEquals(5555, lues.dernierPort)
        assertTrue(lues.verifierMisesAJour)
    }

    @Test
    fun `le dernier televiseur et son nom survivent a un redemarrage`() = runTest {
        PreferencesWindows(fichier()).apply {
            retenir("192.168.2.135", 5555)
            retenirNom("192.168.2.135", "TCL Smart TV Pro")
        }

        val relues = PreferencesWindows(fichier()).lire()

        assertEquals("192.168.2.135", relues.dernierHote)
        assertEquals(mapOf("192.168.2.135" to "TCL Smart TV Pro"), relues.nomsConnus)
    }

    @Test
    fun `un nom vide n'est pas retenu`() = runTest {
        PreferencesWindows(fichier()).retenirNom("192.168.2.135", "  ")

        assertTrue(PreferencesWindows(fichier()).lire().nomsConnus.isEmpty())
    }

    @Test
    fun `un fichier abime rend les valeurs par defaut au lieu d'empecher le demarrage`() = runTest {
        fichier().writeText("{ pas du json")

        val lues = PreferencesWindows(fichier()).lire()

        assertEquals("", lues.dernierHote)
    }

    @Test
    fun `refuser la verification des mises a jour est retenu`() = runTest {
        PreferencesWindows(fichier()).majVerificationMisesAJour(false)

        assertFalse(PreferencesWindows(fichier()).lire().verifierMisesAJour)
        assertFalse(File(dossier.root, "preferences.json.tmp").exists())
    }
}
