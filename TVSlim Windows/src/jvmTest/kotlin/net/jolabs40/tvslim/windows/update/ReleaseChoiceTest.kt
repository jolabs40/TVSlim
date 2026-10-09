package net.jolabs40.tvslim.windows.update

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which releases the updater offers, and above all which it refuses. */
class ReleaseChoiceTest {

    private val repository = "jolabs40/TVSlim"

    private fun file(name: String, tag: String) =
        PublishedFile(name, "https://github.com/$repository/releases/download/$tag/$name", 1_000)

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        files: List<PublishedFile>? = null,
    ): GithubRelease {
        val version = tag.removePrefix("windows-v")
        return GithubRelease(
            tag = tag,
            body = "Notes $version",
            draft = draft,
            prerelease = prerelease,
            page = "https://github.com/$repository/releases/tag/$tag",
            assets = files ?: listOf(
                file("TVSlim-Windows-$version.msi", tag),
                file("TVSlim-Windows-$version.msi.sig", tag),
                file("TVSlim-Windows-$version-portable.zip", tag),
            ),
        )
    }

    @Test
    fun `versions compare number by number, not as text`() {
        assertTrue(Version.read("1.10.0")!! > Version.read("1.9.9")!!)
        assertEquals(Version(2, 0, 13), Version.read(" 2.0.13 "))
        listOf("1.2", "1.2.3.4", "v1.2.3", "1.2.3-beta", "", "a.b.c").forEach {
            assertNull(it, Version.read(it))
        }
    }

    @Test
    fun `the highest Windows version is picked and Android releases are ignored`() {
        val update = ReleaseChoice.choose(
            listOf(
                release("windows-v1.1.0"),
                GithubRelease(tag = "android-v3.0.0", page = "https://github.com/$repository/releases/tag/android-v3.0.0"),
                release("windows-v1.2.0"),
                release("windows-v1.0.5"),
            ),
            current = Version(1, 0, 0),
            repository = repository,
        )

        assertEquals(Version(1, 2, 0), update?.version)
        assertEquals("TVSlim-Windows-1.2.0.msi", update?.installer?.name)
        assertEquals("TVSlim-Windows-1.2.0.msi.sig", update?.signature?.name)
        assertEquals("Notes 1.2.0", update?.notes)
    }

    @Test
    fun `nothing when the running version is already the latest`() {
        assertNull(ReleaseChoice.choose(listOf(release("windows-v1.2.0")), Version(1, 2, 0), repository))
        assertNull(ReleaseChoice.choose(listOf(release("windows-v1.2.0")), Version(1, 3, 0), repository))
    }

    @Test
    fun `drafts and prereleases are never offered`() {
        val releases = listOf(
            release("windows-v2.0.0", draft = true),
            release("windows-v1.5.0", prerelease = true),
        )

        assertNull(ReleaseChoice.choose(releases, Version(1, 0, 0), repository))
    }

    @Test
    fun `a release without a signature is not offered`() {
        val tag = "windows-v1.2.0"
        val withoutSignature = release(tag, files = listOf(file("TVSlim-Windows-1.2.0.msi", tag)))

        assertNull(ReleaseChoice.choose(listOf(withoutSignature), Version(1, 0, 0), repository))
    }

    @Test
    fun `a file hosted outside the repository is rejected`() {
        val tag = "windows-v1.2.0"
        val hijacked = release(
            tag,
            files = listOf(
                PublishedFile("TVSlim-Windows-1.2.0.msi", "https://example.invalid/TVSlim-Windows-1.2.0.msi"),
                file("TVSlim-Windows-1.2.0.msi.sig", tag),
            ),
        )

        assertNull(ReleaseChoice.choose(listOf(hijacked), Version(1, 0, 0), repository))
        assertFalse(ReleaseChoice.isRepositoryUrl("https://github.com/other/TVSlim/releases/download/x/a.msi", repository))
        assertFalse(
            ReleaseChoice.isRepositoryUrl(
                "https://github.com/jolabs40/TVSlim/releases/download/../../other/repo/a.msi",
                repository,
            ),
        )
        assertFalse(ReleaseChoice.isRepositoryUrl("http://github.com/jolabs40/TVSlim/releases/download/x/a.msi", repository))
        assertTrue(ReleaseChoice.isRepositoryUrl("https://github.com/jolabs40/tvslim/releases/download/windows-v1.2.0/a.msi", repository))
    }

    @Test
    fun `the GitHub API response parses, unknown fields included`() {
        val response = """
            [{"url":"https://api.github.com/repos/jolabs40/TVSlim/releases/1","tag_name":"windows-v1.0.1",
              "name":"TV Slim 1.0.1","draft":false,"prerelease":false,
              "html_url":"https://github.com/jolabs40/TVSlim/releases/tag/windows-v1.0.1",
              "body":"Fixes","author":{"login":"jolabs40"},
              "assets":[
                {"name":"TVSlim-Windows-1.0.1.msi","size":84000000,"content_type":"application/x-msi",
                 "browser_download_url":"https://github.com/jolabs40/TVSlim/releases/download/windows-v1.0.1/TVSlim-Windows-1.0.1.msi"},
                {"name":"TVSlim-Windows-1.0.1.msi.sig","size":89,
                 "browser_download_url":"https://github.com/jolabs40/TVSlim/releases/download/windows-v1.0.1/TVSlim-Windows-1.0.1.msi.sig"}
              ]}]
        """.trimIndent()

        val releases = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(GithubRelease.serializer()), response)
        val update = ReleaseChoice.choose(releases, Version(1, 0, 0), repository)

        assertEquals(Version(1, 0, 1), update?.version)
        assertEquals("Fixes", update?.notes)
        assertNull(update?.portable)
        assertEquals("https://github.com/jolabs40/TVSlim/releases/tag/windows-v1.0.1", update?.page)
    }
}
