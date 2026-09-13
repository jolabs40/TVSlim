package net.jolabs40.tvslim.windows.maj

import net.jolabs40.tvslim.windows.InfosApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * L'installateur ne s'exécute que signé par la clé de TV Slim. On signe ici avec l'outil réellement
 * utilisé à la publication (`outils/SignerMiseAJour.java`), pas avec une copie de sa logique : le
 * jour où l'un des deux change de message, ce test échoue avant qu'une mise à jour soit refusée
 * chez tout le monde.
 */
class VerificationSignatureTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private val paire = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publique = Base64.getEncoder().encodeToString(paire.public.encoded)
    private val privee = Base64.getEncoder().encodeToString(paire.private.encoded)

    private fun installateur(contenu: String = "un installateur MSI, en vrai") =
        dossier.newFile("TVSlim-Windows-1.2.3.msi").apply { writeText(contenu) }

    private fun signerAvecOutil(fichier: File, version: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val outil = File(System.getProperty("tvslim.projet"), "outils/SignerMiseAJour.java").path
        val processus = ProcessBuilder(java, outil, fichier.path, version)
            .redirectErrorStream(true)
            .apply { environment()["TVSLIM_CLE_SIGNATURE"] = privee }
            .start()
        val sortie = processus.inputStream.bufferedReader().readText()
        assertEquals(sortie, 0, processus.waitFor())
        return File(fichier.path + ".sig").readText()
    }

    /** L'outil de vérification de la publication, lancé tel quel : son code de sortie. */
    private fun verifierAvecOutil(fichier: File, version: String, clePublique: String): Int {
        val proprietes = File(dossier.newFolder(), "gradle.properties").apply {
            writeText("# clé de test\nclePubliqueMisesAJour=$clePublique\n")
        }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val outil = File(System.getProperty("tvslim.projet"), "outils/VerifierMiseAJour.java").path
        val processus = ProcessBuilder(java, outil, fichier.path, version, proprietes.path)
            .redirectErrorStream(true)
            .start()
        processus.inputStream.bufferedReader().readText()
        return processus.waitFor()
    }

    @Test
    fun `l'outil de verification de la publication s'accorde avec l'application`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")
        val autreCle = Base64.getEncoder()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)

        assertEquals(0, verifierAvecOutil(msi, "1.2.3", publique))
        assertEquals(1, verifierAvecOutil(msi, "1.2.4", publique))
        assertEquals(1, verifierAvecOutil(msi, "1.2.3", autreCle))

        msi.appendText("!")
        assertEquals(1, verifierAvecOutil(msi, "1.2.3", publique))
        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, publique))
    }

    @Test
    fun `la cle publique embarquee dans l'application est une cle Ed25519 lisible`() {
        val cle = InfosApp.CLE_PUBLIQUE_MISES_A_JOUR
        assertTrue("aucune clé publique : toutes les mises à jour seraient refusées", cle.isNotBlank())

        val lue = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(cle)))

        // Une clé publique Ed25519 en X.509 : douze octets d'en-tête, trente-deux de clé.
        assertEquals(44, lue.encoded.size)
    }

    @Test
    fun `une signature produite par l'outil de publication est acceptee`() {
        val msi = installateur()

        assertTrue(VerificationSignature.verifier(msi, "1.2.3", signerAvecOutil(msi, "1.2.3"), publique))
    }

    @Test
    fun `un installateur modifie apres signature est refuse`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")

        msi.appendText("!")

        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, publique))
    }

    @Test
    fun `une signature authentique ne vaut pas pour une autre version`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")

        assertFalse(VerificationSignature.verifier(msi, "1.2.4", signature, publique))
    }

    @Test
    fun `une autre cle, une signature illisible ou l'absence de cle sont refusees`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")
        val autreCle = Base64.getEncoder()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)

        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, autreCle))
        assertFalse(VerificationSignature.verifier(msi, "1.2.3", "pas de la base64 !", publique))
        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, ""))
    }
}
