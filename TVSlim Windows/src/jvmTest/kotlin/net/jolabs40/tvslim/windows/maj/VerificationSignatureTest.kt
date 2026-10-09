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
 * The installer only runs when signed with TV Slim's key. Signs with the actual release tool
 * (`outils/SignerMiseAJour.java`), not a copy of its logic: if either side changes the signed message, this
 * test fails before every install starts refusing updates.
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

    /** Runs the release verification tool as is and returns its exit code. */
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
    fun `the release verification tool agrees with the app`() {
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
    fun `the public key embedded in the app is a readable Ed25519 key`() {
        val cle = InfosApp.CLE_PUBLIQUE_MISES_A_JOUR
        assertTrue("aucune clé publique : toutes les mises à jour seraient refusées", cle.isNotBlank())

        val lue = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(cle)))

        // X.509-encoded Ed25519 public key: 12-byte header plus 32-byte key.
        assertEquals(44, lue.encoded.size)
    }

    @Test
    fun `a signature from the release tool is accepted`() {
        val msi = installateur()

        assertTrue(VerificationSignature.verifier(msi, "1.2.3", signerAvecOutil(msi, "1.2.3"), publique))
    }

    @Test
    fun `an installer modified after signing is rejected`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")

        msi.appendText("!")

        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, publique))
    }

    @Test
    fun `a genuine signature does not hold for another version`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")

        assertFalse(VerificationSignature.verifier(msi, "1.2.4", signature, publique))
    }

    @Test
    fun `another key, an unreadable signature or a missing key is rejected`() {
        val msi = installateur()
        val signature = signerAvecOutil(msi, "1.2.3")
        val autreCle = Base64.getEncoder()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)

        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, autreCle))
        assertFalse(VerificationSignature.verifier(msi, "1.2.3", "pas de la base64 !", publique))
        assertFalse(VerificationSignature.verifier(msi, "1.2.3", signature, ""))
    }
}
