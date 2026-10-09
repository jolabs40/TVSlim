package net.jolabs40.tvslim.tvapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Picking the TV APK to install from the GitHub releases response. */
class TvReleaseChoiceTest {

    private fun release(
        tag: String,
        files: List<String>,
        draft: Boolean = false,
        prerelease: Boolean = false,
        url: (String) -> String = { "https://github.com/jolabs40/TVSlim/releases/download/$tag/$it" },
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[${
        files.joinToString(",") { """{"name":"$it","browser_download_url":"${url(it)}","size":1295851}""" }
    }]}"""

    private fun response(vararg releases: String) = "[${releases.joinToString(",")}]"

    @Test
    fun `the highest Android version wins, not the latest release`() {
        val chosen = TvReleaseChoice.choose(
            response(
                release("windows-v1.6.0", listOf("TVSlim-Windows-1.6.0.msi")),
                release("android-v1.0.0", listOf("TVSlim-Remote-1.0.0.apk", "TVSlim-TV-1.0.0.apk")),
                release("android-v1.10.0", listOf("TVSlim-Remote-1.10.0.apk", "TVSlim-TV-1.10.0.apk")),
                release("android-v1.2.0", listOf("TVSlim-TV-1.2.0.apk")),
            ),
        )!!
        assertEquals("1.10.0", chosen.version)
        assertEquals(11000L, chosen.versionCode)
        assertEquals("TVSlim-TV-1.10.0.apk", chosen.fileName)
        assertEquals("https://github.com/jolabs40/TVSlim/releases/download/android-v1.10.0/TVSlim-TV-1.10.0.apk", chosen.url)
        assertEquals(1295851L, chosen.size)
    }

    @Test
    fun `drafts, prereleases and releases without a TV APK are ignored`() {
        val chosen = TvReleaseChoice.choose(
            response(
                release("android-v1.3.0", listOf("TVSlim-TV-1.3.0.apk"), draft = true),
                release("android-v1.2.0", listOf("TVSlim-TV-1.2.0.apk"), prerelease = true),
                release("android-v1.1.0", listOf("TVSlim-Remote-1.1.0.apk")),
                release("android-v1.0.0", listOf("TVSlim-TV-1.0.0.apk")),
            ),
        )!!
        assertEquals("1.0.0", chosen.version)
    }

    @Test
    fun `a link outside the repository downloads is rejected`() {
        val elsewhere = response(release("android-v1.1.0", listOf("TVSlim-TV-1.1.0.apk")) { "https://example.com/$it" })
        val detour = response(
            release("android-v1.1.0", listOf("TVSlim-TV-1.1.0.apk")) {
                "https://github.com/jolabs40/TVSlim/releases/download/../../autre/$it"
            },
        )
        val cleartext = response(release("android-v1.1.0", listOf("TVSlim-TV-1.1.0.apk")) { "http://github.com/jolabs40/TVSlim/releases/download/x/$it" })
        assertNull(TvReleaseChoice.choose(elsewhere))
        assertNull(TvReleaseChoice.choose(detour))
        assertNull(TvReleaseChoice.choose(cleartext))
    }

    @Test
    fun `an unreadable or empty response yields nothing`() {
        assertNull(TvReleaseChoice.choose("{\"message\":\"API rate limit exceeded\"}"))
        assertNull(TvReleaseChoice.choose("[]"))
        assertNull(TvReleaseChoice.choose("pas du JSON"))
    }

    @Test
    fun `the versionCode follows the build formula`() {
        assertEquals(10000L, TvReleaseChoice.versionCode(1, 0, 0))
        assertEquals(10100L, TvReleaseChoice.versionCode(1, 1, 0))
        assertEquals(20305L, TvReleaseChoice.versionCode(2, 3, 5))
    }
}
