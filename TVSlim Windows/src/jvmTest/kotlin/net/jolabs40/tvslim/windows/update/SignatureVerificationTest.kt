package net.jolabs40.tvslim.windows.update

import net.jolabs40.tvslim.windows.AppInfo
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
 * (`tools/SignUpdate.java`), not a copy of its logic: if either side changes the signed message, this
 * test fails before every install starts refusing updates.
 */
class SignatureVerificationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
    private val privateKey = Base64.getEncoder().encodeToString(pair.private.encoded)

    private fun installer(content: String = "a real MSI installer") =
        folder.newFile("TVSlim-Windows-1.2.3.msi").apply { writeText(content) }

    private fun signWithTool(file: File, version: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val tool = File(System.getProperty("tvslim.project"), "tools/SignUpdate.java").path
        val process = ProcessBuilder(java, tool, file.path, version)
            .redirectErrorStream(true)
            .apply { environment()["TVSLIM_CLE_SIGNATURE"] = privateKey }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
        return File(file.path + ".sig").readText()
    }

    /** Runs the release verification tool as is and returns its exit code. */
    private fun verifyWithTool(file: File, version: String, publicKeyBase64: String): Int {
        val properties = File(folder.newFolder(), "gradle.properties").apply {
            writeText("# test key\nupdatesPublicKey=$publicKeyBase64\n")
        }
        val java = File(System.getProperty("java.home"), "bin/java").path
        val tool = File(System.getProperty("tvslim.project"), "tools/VerifyUpdate.java").path
        val process = ProcessBuilder(java, tool, file.path, version, properties.path)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText()
        return process.waitFor()
    }

    @Test
    fun `the release verification tool agrees with the app`() {
        val msi = installer()
        val signature = signWithTool(msi, "1.2.3")
        val otherKey = Base64.getEncoder()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)

        assertEquals(0, verifyWithTool(msi, "1.2.3", publicKey))
        assertEquals(1, verifyWithTool(msi, "1.2.4", publicKey))
        assertEquals(1, verifyWithTool(msi, "1.2.3", otherKey))

        msi.appendText("!")
        assertEquals(1, verifyWithTool(msi, "1.2.3", publicKey))
        assertFalse(SignatureVerification.check(msi, "1.2.3", signature, publicKey))
    }

    @Test
    fun `the public key embedded in the app is a readable Ed25519 key`() {
        val key = AppInfo.UPDATE_PUBLIC_KEY
        assertTrue("no public key: every update would be rejected", key.isNotBlank())

        val loaded = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))

        // X.509-encoded Ed25519 public key: 12-byte header plus 32-byte key.
        assertEquals(44, loaded.encoded.size)
    }

    @Test
    fun `a signature from the release tool is accepted`() {
        val msi = installer()

        assertTrue(SignatureVerification.check(msi, "1.2.3", signWithTool(msi, "1.2.3"), publicKey))
    }

    @Test
    fun `an installer modified after signing is rejected`() {
        val msi = installer()
        val signature = signWithTool(msi, "1.2.3")

        msi.appendText("!")

        assertFalse(SignatureVerification.check(msi, "1.2.3", signature, publicKey))
    }

    @Test
    fun `a genuine signature does not hold for another version`() {
        val msi = installer()
        val signature = signWithTool(msi, "1.2.3")

        assertFalse(SignatureVerification.check(msi, "1.2.4", signature, publicKey))
    }

    @Test
    fun `another key, an unreadable signature or a missing key is rejected`() {
        val msi = installer()
        val signature = signWithTool(msi, "1.2.3")
        val otherKey = Base64.getEncoder()
            .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public.encoded)

        assertFalse(SignatureVerification.check(msi, "1.2.3", signature, otherKey))
        assertFalse(SignatureVerification.check(msi, "1.2.3", "not base64!", publicKey))
        assertFalse(SignatureVerification.check(msi, "1.2.3", signature, ""))
    }
}
