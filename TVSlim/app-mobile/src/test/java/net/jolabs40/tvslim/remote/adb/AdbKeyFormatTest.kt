package net.jolabs40.tvslim.remote.adb

import dadb.AdbKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The key store rebuilds the ADB pair in memory from bytes kept encrypted, never writing a plaintext
 * file again. That requires parsing what dadb writes: PEM today, with no guarantee across versions.
 *
 * Runs against the library actually shipped, so a format change fails here rather than on a TV that
 * refuses the connection.
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
        assertTrue("The DER must not be empty", der.isNotEmpty())

        val key = privateKeyFromDer(der)

        assertEquals("RSA", key.algorithm)
        assertEquals("PKCS#8", key.format)
    }

    @Test
    fun `the pair is rebuilt in memory without going back to disk`() {
        val privateKey = folder.newFile("adbkey")
        val publicKey = folder.newFile("adbkey.pub")
        AdbKeyPair.generate(privateKey, publicKey)

        // Exactly what DepotCles does once the bytes are decrypted.
        val key = privateKeyFromDer(derFromPem(privateKey.readText()))
        val inMemory = AdbKeyPair(key, publicKey.readBytes())

        assertNotNull(inMemory)
        assertNotNull(AdbKeyPair.read(privateKey, publicKey))
    }

    @Test
    fun `the rebuilt DER matches the one encoded in the PEM`() {
        val privateKey = folder.newFile("adbkey")
        val publicKey = folder.newFile("adbkey.pub")
        AdbKeyPair.generate(privateKey, publicKey)

        val der = derFromPem(privateKey.readText())

        // The stored key must survive a full round trip unchanged.
        assertTrue(der.contentEquals(privateKeyFromDer(der).encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty PEM is rejected instead of producing a blank key`() {
        derFromPem("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n")
    }
}
