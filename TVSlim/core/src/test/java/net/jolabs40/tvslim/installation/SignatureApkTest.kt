package net.jolabs40.tvslim.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * L'empreinte du certificat d'un APK, lue dans son bloc de signature. Les APK de test portent le vrai
 * manifeste de l'application TV, signé par deux clés jetables (`apksigner`, 2026-10-06) ; les empreintes
 * attendues sont celles qu'affiche `apksigner verify --print-certs`.
 */
class SignatureApkTest {

    private val fixtures = File("src/test/fixtures/apk")

    @Test
    fun `le certificat se lit dans le bloc v3`() {
        assertEquals(CLE_A, SignatureApk.empreinteCertificat(File(fixtures, "tv-cle-a.apk")))
        assertEquals(CLE_B, SignatureApk.empreinteCertificat(File(fixtures, "tv-cle-b.apk")))
    }

    @Test
    fun `sans bloc v3, le bloc v2 suffit`() {
        assertEquals(CLE_A, SignatureApk.empreinteCertificat(File(fixtures, "tv-cle-a-v2.apk")))
    }

    @Test
    fun `un APK non signe n'a pas d'empreinte`() {
        assertNull(SignatureApk.empreinteCertificat(File(fixtures, "tv-non-signe.apk")))
    }

    @Test
    fun `un fichier tronque ou etranger n'a pas d'empreinte, et ne fait rien tomber`() {
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
     * Sur la vraie release, quand elle a été construite et signée en local : l'empreinte doit être celle
     * de `empreinteCertificat` — celle que les applications compareront à l'APK téléchargé.
     */
    @Test
    fun `la release locale de l'application TV porte l'empreinte attendue`() {
        val release = File("../app-tv/build/outputs/apk/release/app-tv-release.apk")
        assumeTrue("Pas de release signée en local", release.exists())
        val attendue = File("../gradle.properties").readLines()
            .first { it.startsWith("empreinteCertificat=") }.substringAfter('=').trim()
        val lue = SignatureApk.empreinteCertificat(release)
        // Une release construite sans le coffre n'est pas signée : rien à comparer.
        assumeTrue("Release non signée", lue != null)
        assertEquals(attendue, lue)
    }

    companion object {
        const val CLE_A = "6df51f75ceaffce807bf80736efba8e53e7197b264c532fa5eed019373d77b25"
        const val CLE_B = "312bbf40411242b6e327a01eadfe0d369a2bd38ae35531d7dac765b298c79149"
    }
}
