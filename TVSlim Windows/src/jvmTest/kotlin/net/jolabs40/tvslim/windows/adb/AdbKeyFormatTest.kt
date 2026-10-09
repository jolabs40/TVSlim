package net.jolabs40.tvslim.windows.adb

import dadb.AdbKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The key store rebuilds the ADB pair in memory from decrypted bytes, so it must parse what dadb writes: PEM
 * today, with no guarantee across versions. Same check as on the Android companion, same library.
 */
class AdbKeyFormatTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the private key written by dadb reads back as PKCS8`() {
        val privateKey = folder.newFile("adbkey")
        val publicKey = folder.newFile("adbkey.pub")
        AdbKeyPair.generate(privateKey, publicKey)

        val der = derFromPem(privateKey.readText())
        assertTrue("Le DER ne doit pas être vide", der.isNotEmpty())

        val key = privateKeyFromDer(der)
        assertEquals("RSA", key.algorithm)
        assertEquals("PKCS#8", key.format)
    }

    @Test
    fun `the pair is rebuilt in memory without going back to disk`() {
        val privateKey = folder.newFile("adbkey")
        val publicKey = folder.newFile("adbkey.pub")
        AdbKeyPair.generate(privateKey, publicKey)

        val key = privateKeyFromDer(derFromPem(privateKey.readText()))
        assertNotNull(AdbKeyPair(key, publicKey.readBytes()))
    }

    @Test
    fun `the rebuilt DER matches the one encoded in the PEM`() {
        val privateKey = folder.newFile("adbkey")
        val publicKey = folder.newFile("adbkey.pub")
        AdbKeyPair.generate(privateKey, publicKey)

        val der = derFromPem(privateKey.readText())
        assertTrue(der.contentEquals(privateKeyFromDer(der).encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty PEM is rejected instead of producing a blank key`() {
        derFromPem("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n")
    }
}
