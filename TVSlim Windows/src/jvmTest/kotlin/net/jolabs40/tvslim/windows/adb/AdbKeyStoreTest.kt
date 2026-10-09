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
class AdbKeyStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Reversible and recognizable, to prove the store goes through the protection. */
    private object InvertedProtection : DataProtection {
        override fun protect(data: ByteArray) = data.reversedArray() + MARK
        override fun unprotect(protectedData: ByteArray): ByteArray {
            require(protectedData.takeLast(MARK.size).toByteArray().contentEquals(MARK)) { "not protected" }
            return protectedData.copyOfRange(0, protectedData.size - MARK.size).reversedArray()
        }

        private val MARK = "#protected".toByteArray()
    }

    /** Both key files' bytes: if unchanged, the key was read back, not regenerated. */
    private fun fingerprint(keys: File) =
        File(keys, "adbkey.pub").readBytes() + File(keys, "adbkey.dpapi").readBytes()

    @Test
    fun `a created pair reads back identically from another store`() {
        val keys = folder.newFolder("keys")
        assertNotNull(AdbKeyStore(keys, InvertedProtection).pair())
        val before = fingerprint(keys)

        assertNotNull(AdbKeyStore(keys, InvertedProtection).pair())

        assertArrayEquals(before, fingerprint(keys))
        assertFalse(keys.listFiles()!!.any { it.name.contains("illisible") })
    }

    @Test
    fun `no plaintext key is left on disk`() {
        val keys = folder.newFolder("keys")
        AdbKeyStore(keys, InvertedProtection).pair()

        val files = keys.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("adbkey.dpapi", "adbkey.pub"), files)

        val privateKey = File(keys, "adbkey.dpapi").readBytes()
        assertFalse(String(privateKey, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        // The file only decrypts through the protection.
        assertNotNull(privateKeyFromDer(InvertedProtection.unprotect(privateKey)))
    }

    @Test
    fun `an unreadable key is set aside, not deleted`() {
        val keys = folder.newFolder("keys")
        AdbKeyStore(keys, InvertedProtection).pair()
        File(keys, "adbkey.dpapi").writeText("corrupt")

        val fresh = AdbKeyStore(keys, InvertedProtection).pair()

        assertNotNull(fresh)
        assertTrue(keys.listFiles()!!.any { it.name.startsWith("adbkey.dpapi.illisible-") })
        assertTrue(File(keys, "adbkey.dpapi").exists())
    }

    @Test
    fun `DPAPI encrypts and decrypts under this account`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val secret = "a key that must stay unreadable".toByteArray()

        val protectedItem = DpapiProtection.protect(secret)

        assertFalse(protectedItem.contentEquals(secret))
        assertArrayEquals(secret, DpapiProtection.unprotect(protectedItem))
    }

    @Test
    fun `the real pair is stored and read back with DPAPI`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val keys = folder.newFolder("keys")
        assertNotNull(AdbKeyStore(keys).pair())
        val before = fingerprint(keys)

        assertNotNull(AdbKeyStore(keys).pair())

        assertArrayEquals(before, fingerprint(keys))
        assertFalse(keys.listFiles()!!.any { it.name.contains("illisible") })
    }
}
