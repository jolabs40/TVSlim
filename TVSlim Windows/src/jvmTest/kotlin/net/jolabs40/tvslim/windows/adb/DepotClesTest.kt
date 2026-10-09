package net.jolabs40.tvslim.windows.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The ADB key is shell access to every authorized TV: it must never sit in plaintext on disk, and it must read
 * back unchanged, or every TV asks for authorization again.
 */
class DepotClesTest {

    @get:Rule
    val dossier = TemporaryFolder()

    /** Reversible and recognizable, to prove the store goes through the protection. */
    private object ProtectionInversee : ProtectionDonnees {
        override fun proteger(donnees: ByteArray) = donnees.reversedArray() + MARQUE
        override fun lever(protegees: ByteArray): ByteArray {
            require(protegees.takeLast(MARQUE.size).toByteArray().contentEquals(MARQUE)) { "non protégé" }
            return protegees.copyOfRange(0, protegees.size - MARQUE.size).reversedArray()
        }

        private val MARQUE = "#protege".toByteArray()
    }

    /** Both key files' bytes: if unchanged, the key was read back, not regenerated. */
    private fun empreinte(cles: File) =
        File(cles, "adbkey.pub").readBytes() + File(cles, "adbkey.dpapi").readBytes()

    @Test
    fun `a created pair reads back identically from another store`() {
        val cles = dossier.newFolder("cles")
        assertNotNull(DepotCles(cles, ProtectionInversee).paire())
        val avant = empreinte(cles)

        assertNotNull(DepotCles(cles, ProtectionInversee).paire())

        assertArrayEquals(avant, empreinte(cles))
        assertFalse(cles.listFiles()!!.any { it.name.contains("illisible") })
    }

    @Test
    fun `no plaintext key is left on disk`() {
        val cles = dossier.newFolder("cles")
        DepotCles(cles, ProtectionInversee).paire()

        val fichiers = cles.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("adbkey.dpapi", "adbkey.pub"), fichiers)

        val prive = File(cles, "adbkey.dpapi").readBytes()
        assertFalse(String(prive, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        // The file only decrypts through the protection.
        assertNotNull(clePriveeDepuisDer(ProtectionInversee.lever(prive)))
    }

    @Test
    fun `an unreadable key is set aside, not deleted`() {
        val cles = dossier.newFolder("cles")
        DepotCles(cles, ProtectionInversee).paire()
        File(cles, "adbkey.dpapi").writeText("abîmé")

        val nouvelle = DepotCles(cles, ProtectionInversee).paire()

        assertNotNull(nouvelle)
        assertTrue(cles.listFiles()!!.any { it.name.startsWith("adbkey.dpapi.illisible-") })
        assertTrue(File(cles, "adbkey.dpapi").exists())
    }

    @Test
    fun `DPAPI encrypts and decrypts under this account`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val secret = "une clé qui ne doit pas se lire".toByteArray()

        val protege = ProtectionDpapi.proteger(secret)

        assertFalse(protege.contentEquals(secret))
        assertArrayEquals(secret, ProtectionDpapi.lever(protege))
    }

    @Test
    fun `the real pair is stored and read back with DPAPI`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val cles = dossier.newFolder("cles")
        assertNotNull(DepotCles(cles).paire())
        val avant = empreinte(cles)

        assertNotNull(DepotCles(cles).paire())

        assertArrayEquals(avant, empreinte(cles))
        assertFalse(cles.listFiles()!!.any { it.name.contains("illisible") })
    }
}
