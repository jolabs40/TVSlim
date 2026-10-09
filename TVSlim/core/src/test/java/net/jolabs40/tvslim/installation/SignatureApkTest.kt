package net.jolabs40.tvslim.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Reads an APK's certificate digest from its signing block. The fixtures carry the real TV app manifest, signed
 * with two throwaway keys; expected digests are those printed by `apksigner verify --print-certs`.
 */
class SignatureApkTest {

    private val fixtures = File("src/test/fixtures/apk")

    @Test
    fun `the certificate is read from the v3 block`() {
        assertEquals(CLE_A, SignatureApk.empreinteCertificat(File(fixtures, "tv-cle-a.apk")))
        assertEquals(CLE_B, SignatureApk.empreinteCertificat(File(fixtures, "tv-cle-b.apk")))
    }

    @Test
    fun `without a v3 block, the v2 block is enough`() {
        assertEquals(CLE_A, SignatureApk.empreinteCertificat(File(fixtures, "tv-cle-a-v2.apk")))
    }

    @Test
    fun `an unsigned APK has no digest`() {
        assertNull(SignatureApk.empreinteCertificat(File(fixtures, "tv-non-signe.apk")))
    }

    @Test
    fun `a truncated or foreign file has no digest and does not throw`() {
        val complet = File(fixtures, "tv-cle-a.apk").readBytes()
        val tronque = File.createTempFile("tronque", ".apk").apply {
            deleteOnExit()
            writeBytes(complet.copyOf(complet.size / 2))
        }
        val texte = File.createTempFile("texte", ".apk").apply {
            deleteOnExit()
            writeText("Ce n'est pas un APK.")
        }
        assertNull(SignatureApk.empreinteCertificat(tronque))
        assertNull(SignatureApk.empreinteCertificat(texte))
        assertNull(SignatureApk.empreinteCertificat(File(fixtures, "absent.apk")))
    }

    /**
     * Runs only when a signed release was built locally. Its digest must match `empreinteCertificat`, which the
     * apps compare against the downloaded APK.
     */
    @Test
    fun `the local TV app release carries the expected digest`() {
        val release = File("../app-tv/build/outputs/apk/release/app-tv-release.apk")
        assumeTrue("Pas de release signée en local", release.exists())
        val attendue = File("../gradle.properties").readLines()
            .first { it.startsWith("empreinteCertificat=") }.substringAfter('=').trim()
        val lue = SignatureApk.empreinteCertificat(release)
        // A release built without the signing key is unsigned: nothing to compare.
        assumeTrue("Release non signée", lue != null)
        assertEquals(attendue, lue)
    }

    companion object {
        const val CLE_A = "6df51f75ceaffce807bf80736efba8e53e7197b264c532fa5eed019373d77b25"
        const val CLE_B = "312bbf40411242b6e327a01eadfe0d369a2bd38ae35531d7dac765b298c79149"
    }
}
