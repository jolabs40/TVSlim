package net.jolabs40.tvslim.applicationtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Picking the TV APK to install from the GitHub releases response. */
class ChoixPublicationTvTest {

    private fun publication(
        tag: String,
        fichiers: List<String>,
        brouillon: Boolean = false,
        preversion: Boolean = false,
        url: (String) -> String = { "https://github.com/jolabs40/TVSlim/releases/download/$tag/$it" },
    ) = """{"tag_name":"$tag","draft":$brouillon,"prerelease":$preversion,"assets":[${
        fichiers.joinToString(",") { """{"name":"$it","browser_download_url":"${url(it)}","size":1295851}""" }
    }]}"""

    private fun reponse(vararg publications: String) = "[${publications.joinToString(",")}]"

    @Test
    fun `the highest Android version wins, not the latest release`() {
        val choisie = ChoixPublicationTv.choisir(
            reponse(
                publication("windows-v1.6.0", listOf("TVSlim-Windows-1.6.0.msi")),
                publication("android-v1.0.0", listOf("TVSlim-Remote-1.0.0.apk", "TVSlim-TV-1.0.0.apk")),
                publication("android-v1.10.0", listOf("TVSlim-Remote-1.10.0.apk", "TVSlim-TV-1.10.0.apk")),
                publication("android-v1.2.0", listOf("TVSlim-TV-1.2.0.apk")),
            ),
        )!!
        assertEquals("1.10.0", choisie.version)
        assertEquals(11000L, choisie.versionCode)
        assertEquals("TVSlim-TV-1.10.0.apk", choisie.nomFichier)
        assertEquals("https://github.com/jolabs40/TVSlim/releases/download/android-v1.10.0/TVSlim-TV-1.10.0.apk", choisie.url)
        assertEquals(1295851L, choisie.taille)
    }

    @Test
    fun `drafts, prereleases and releases without a TV APK are ignored`() {
        val choisie = ChoixPublicationTv.choisir(
            reponse(
                publication("android-v1.3.0", listOf("TVSlim-TV-1.3.0.apk"), brouillon = true),
                publication("android-v1.2.0", listOf("TVSlim-TV-1.2.0.apk"), preversion = true),
                publication("android-v1.1.0", listOf("TVSlim-Remote-1.1.0.apk")),
                publication("android-v1.0.0", listOf("TVSlim-TV-1.0.0.apk")),
            ),
        )!!
        assertEquals("1.0.0", choisie.version)
    }

    @Test
    fun `a link outside the repository downloads is rejected`() {
        val ailleurs = reponse(publication("android-v1.1.0", listOf("TVSlim-TV-1.1.0.apk")) { "https://example.com/$it" })
        val detour = reponse(
            publication("android-v1.1.0", listOf("TVSlim-TV-1.1.0.apk")) {
                "https://github.com/jolabs40/TVSlim/releases/download/../../autre/$it"
            },
        )
        val clair = reponse(publication("android-v1.1.0", listOf("TVSlim-TV-1.1.0.apk")) { "http://github.com/jolabs40/TVSlim/releases/download/x/$it" })
        assertNull(ChoixPublicationTv.choisir(ailleurs))
        assertNull(ChoixPublicationTv.choisir(detour))
        assertNull(ChoixPublicationTv.choisir(clair))
    }

    @Test
    fun `an unreadable or empty response yields nothing`() {
        assertNull(ChoixPublicationTv.choisir("{\"message\":\"API rate limit exceeded\"}"))
        assertNull(ChoixPublicationTv.choisir("[]"))
        assertNull(ChoixPublicationTv.choisir("pas du JSON"))
    }

    @Test
    fun `the versionCode follows the build formula`() {
        assertEquals(10000L, ChoixPublicationTv.versionCode(1, 0, 0))
        assertEquals(10100L, ChoixPublicationTv.versionCode(1, 1, 0))
        assertEquals(20305L, ChoixPublicationTv.versionCode(2, 3, 5))
    }
}
