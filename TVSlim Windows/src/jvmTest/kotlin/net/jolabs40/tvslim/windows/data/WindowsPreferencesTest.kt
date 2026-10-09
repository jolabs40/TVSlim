package net.jolabs40.tvslim.windows.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsPreferencesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "preferences.json")

    @Test
    fun `without a file, defaults apply`() = runTest {
        val fetched = WindowsPreferences(file()).read()

        assertEquals("", fetched.lastHost)
        assertEquals(5555, fetched.lastPort)
        assertTrue(fetched.checkForUpdates)
    }

    @Test
    fun `the last TV and its name survive a restart`() = runTest {
        WindowsPreferences(file()).apply {
            rememberAddress("192.168.2.135", 5555)
            rememberName("192.168.2.135", "TCL Smart TV Pro")
        }

        val reread = WindowsPreferences(file()).read()

        assertEquals("192.168.2.135", reread.lastHost)
        assertEquals(mapOf("192.168.2.135" to "TCL Smart TV Pro"), reread.knownNames)
    }

    @Test
    fun `a blank name is not saved`() = runTest {
        WindowsPreferences(file()).rememberName("192.168.2.135", "  ")

        assertTrue(WindowsPreferences(file()).read().knownNames.isEmpty())
    }

    @Test
    fun `a corrupt file yields defaults instead of blocking startup`() = runTest {
        file().writeText("{ pas du json")

        val fetched = WindowsPreferences(file()).read()

        assertEquals("", fetched.lastHost)
    }

    @Test
    fun `turning off update checks is saved`() = runTest {
        WindowsPreferences(file()).setUpdateCheck(false)

        assertFalse(WindowsPreferences(file()).read().checkForUpdates)
        assertFalse(File(folder.root, "preferences.json.tmp").exists())
    }
}
