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
    fun `without a file, defaults apply`() = runTest {
        val lues = PreferencesWindows(fichier()).lire()

        assertEquals("", lues.dernierHote)
        assertEquals(5555, lues.dernierPort)
        assertTrue(lues.verifierMisesAJour)
    }

    @Test
    fun `the last TV and its name survive a restart`() = runTest {
        PreferencesWindows(fichier()).apply {
            retenir("192.168.2.135", 5555)
            retenirNom("192.168.2.135", "TCL Smart TV Pro")
        }

        val relues = PreferencesWindows(fichier()).lire()

        assertEquals("192.168.2.135", relues.dernierHote)
        assertEquals(mapOf("192.168.2.135" to "TCL Smart TV Pro"), relues.nomsConnus)
    }

    @Test
    fun `a blank name is not saved`() = runTest {
        PreferencesWindows(fichier()).retenirNom("192.168.2.135", "  ")

        assertTrue(PreferencesWindows(fichier()).lire().nomsConnus.isEmpty())
    }

    @Test
    fun `a corrupt file yields defaults instead of blocking startup`() = runTest {
        fichier().writeText("{ pas du json")

        val lues = PreferencesWindows(fichier()).lire()

        assertEquals("", lues.dernierHote)
    }

    @Test
    fun `turning off update checks is saved`() = runTest {
        PreferencesWindows(fichier()).majVerificationMisesAJour(false)

        assertFalse(PreferencesWindows(fichier()).lire().verifierMisesAJour)
        assertFalse(File(dossier.root, "preferences.json.tmp").exists())
    }
}
