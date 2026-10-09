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
class FormatCleAdbTest {

    @get:Rule
    val dossier = TemporaryFolder()

    @Test
    fun `the private key written by dadb reads back as PKCS8`() {
        val privee = dossier.newFile("adbkey")
        val publique = dossier.newFile("adbkey.pub")
        AdbKeyPair.generate(privee, publique)

        val der = derDepuisPem(privee.readText())
        assertTrue("Le DER ne doit pas être vide", der.isNotEmpty())

        val cle = clePriveeDepuisDer(der)
        assertEquals("RSA", cle.algorithm)
        assertEquals("PKCS#8", cle.format)
    }

    @Test
    fun `the pair is rebuilt in memory without going back to disk`() {
        val privee = dossier.newFile("adbkey")
        val publique = dossier.newFile("adbkey.pub")
        AdbKeyPair.generate(privee, publique)

        val cle = clePriveeDepuisDer(derDepuisPem(privee.readText()))
        assertNotNull(AdbKeyPair(cle, publique.readBytes()))
    }

    @Test
    fun `the rebuilt DER matches the one encoded in the PEM`() {
        val privee = dossier.newFile("adbkey")
        val publique = dossier.newFile("adbkey.pub")
        AdbKeyPair.generate(privee, publique)

        val der = derDepuisPem(privee.readText())
        assertTrue(der.contentEquals(clePriveeDepuisDer(der).encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty PEM is rejected instead of producing a blank key`() {
        derDepuisPem("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n")
    }
}
