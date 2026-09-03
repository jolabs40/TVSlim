package net.jolabs40.tvslim.remote.adb

import dadb.AdbKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Le dépôt de clés reconstruit la paire ADB en mémoire, à partir d'octets gardés chiffrés, sans
 * jamais réécrire de fichier en clair. Cela suppose de savoir relire ce que dadb écrit — du PEM
 * aujourd'hui, rien ne le garantit d'une version à l'autre.
 *
 * Ce test le vérifie sur la bibliothèque réellement embarquée : le jour où le format change, il
 * échoue ici plutôt que sur un téléviseur qui refuse la connexion.
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

        // C'est exactement ce que fait DepotCles une fois les octets déchiffrés.
        val cle = clePriveeDepuisDer(derDepuisPem(privee.readText()))
        val enMemoire = AdbKeyPair(cle, publique.readBytes())

        assertNotNull(enMemoire)
        assertNotNull(AdbKeyPair.read(privee, publique))
    }

    @Test
    fun `le DER reconstruit est identique a celui encode dans le PEM`() {
        val privee = dossier.newFile("adbkey")
        val publique = dossier.newFile("adbkey.pub")
        AdbKeyPair.generate(privee, publique)

        val der = derDepuisPem(privee.readText())

        // La clé rangée au coffre doit pouvoir refaire le tour complet sans se dégrader.
        assertTrue(der.contentEquals(clePriveeDepuisDer(der).encoded))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un PEM vide est refuse plutot que de produire une cle muette`() {
        derDepuisPem("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n")
    }
}
