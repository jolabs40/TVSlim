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
class ApkSignatureTest {

    private val fixtures = File("src/test/fixtures/apk")

    @Test
    fun `the certificate is read from the v3 block`() {
        assertEquals(KEY_A, ApkSignature.certificateFingerprint(File(fixtures, "tv-cle-a.apk")))
        assertEquals(KEY_B, ApkSignature.certificateFingerprint(File(fixtures, "tv-cle-b.apk")))
    }

    @Test
    fun `without a v3 block, the v2 block is enough`() {
        assertEquals(KEY_A, ApkSignature.certificateFingerprint(File(fixtures, "tv-cle-a-v2.apk")))
    }

    @Test
    fun `an unsigned APK has no digest`() {
        assertNull(ApkSignature.certificateFingerprint(File(fixtures, "tv-non-signe.apk")))
    }

    @Test
    fun `a truncated or foreign file has no digest and does not throw`() {
        val complete = File(fixtures, "tv-cle-a.apk").readBytes()
        val truncated = File.createTempFile("truncated", ".apk").apply {
            deleteOnExit()
            writeBytes(complete.copyOf(complete.size / 2))
        }
        val text = File.createTempFile("text", ".apk").apply {
            deleteOnExit()
            writeText("This is not an APK.")
        }
        assertNull(ApkSignature.certificateFingerprint(truncated))
        assertNull(ApkSignature.certificateFingerprint(text))
        assertNull(ApkSignature.certificateFingerprint(File(fixtures, "absent.apk")))
    }

    /**
     * Runs only when a signed release was built locally. Its digest must match `certificateFingerprint`, which the
     * apps compare against the downloaded APK.
     */
    @Test
    fun `the local TV app release carries the expected digest`() {
        val release = File("../app-tv/build/outputs/apk/release/app-tv-release.apk")
        assumeTrue("No signed release built locally", release.exists())
        val expected = File("../gradle.properties").readLines()
            .first { it.startsWith("certificateFingerprint=") }.substringAfter('=').trim()
        val loaded = ApkSignature.certificateFingerprint(release)
        // A release built without the signing key is unsigned: nothing to compare.
        assumeTrue("Release not signed", loaded != null)
        assertEquals(expected, loaded)
    }

    companion object {
        const val KEY_A = "6df51f75ceaffce807bf80736efba8e53e7197b264c532fa5eed019373d77b25"
        const val KEY_B = "312bbf40411242b6e327a01eadfe0d369a2bd38ae35531d7dac765b298c79149"
    }
}
