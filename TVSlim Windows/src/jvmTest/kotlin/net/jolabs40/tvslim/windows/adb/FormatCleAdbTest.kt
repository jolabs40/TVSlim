package net.jolabs40.tvslim.windows.adb

import dadb.AdbKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le dépôt de clés reconstruit la paire ADB en mémoire, à partir d'octets déchiffrés. Cela suppose
 * de savoir relire ce que dadb écrit — du PEM aujourd'hui, rien ne le garantit d'une version à
 * l'autre. Même vérification que sur le compagnon Android, sur la même bibliothèque.
 */
class FormatCleAdbTest {

    @get:Rule
    val dossier = TemporaryFolder()

    @Test
    fun `la cle privee ecrite par dadb se relit en PKCS8`() {
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
    fun `la paire se reconstruit en memoire sans repasser par le disque`() {
        val privee = dossier.newFile("adbkey")
        val publique = dossier.newFile("adbkey.pub")
        AdbKeyPair.generate(privee, publique)

        val cle = clePriveeDepuisDer(derDepuisPem(privee.readText()))
        assertNotNull(AdbKeyPair(cle, publique.readBytes()))
    }

    @Test
    fun `le DER reconstruit est identique a celui encode dans le PEM`() {
        val privee = dossier.newFile("adbkey")
        val publique = dossier.newFile("adbkey.pub")
        AdbKeyPair.generate(privee, publique)

        val der = derDepuisPem(privee.readText())
        assertTrue(der.contentEquals(clePriveeDepuisDer(der).encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un PEM vide est refuse plutot que de produire une cle muette`() {
        derDepuisPem("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n")
    }
}
