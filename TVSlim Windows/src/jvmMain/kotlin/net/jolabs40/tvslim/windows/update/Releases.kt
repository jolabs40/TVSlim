package net.jolabs40.tvslim.windows.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A `major.minor.patch` version, the only form an MSI installer accepts. */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,5})""")

        fun read(text: String): Version? =
            PATTERN.matchEntire(text.trim())?.destructured?.let { (major, minor, patch) ->
                Version(major.toInt(), minor.toInt(), patch.toInt())
            }
    }
}

/** The subset of a GitHub API release that is used here. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val page: String = "",
    val assets: List<PublishedFile> = emptyList(),
)

@Serializable
data class PublishedFile(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

data class AvailableUpdate(
    val version: Version,
    val notes: String,
    val page: String,
    val installer: PublishedFile,
    val signature: PublishedFile,
    val portable: PublishedFile?,
)

/**
 * Picks the release that would replace the running version.
 *
 * The repository also publishes the Android app, so only `windows-v*` tags count, excluding drafts and
 * prereleases. Only assets attached to a release of this repository are downloaded; a link in a release
 * description or elsewhere cannot point the download to another file.
 */
object ReleaseChoice {

    const val TAG_PREFIX = "windows-v"

    fun installerName(version: Version) = "TVSlim-Windows-$version.msi"

    fun signatureName(version: Version) = "TVSlim-Windows-$version.msi.sig"

    fun portableName(version: Version) = "TVSlim-Windows-$version-portable.zip"

    fun choose(
        releases: List<GithubRelease>,
        current: Version,
        repository: String,
    ): AvailableUpdate? {
        val (version, release) = releases
            .asSequence()
            .filter { !it.draft && !it.prerelease && it.tag.startsWith(TAG_PREFIX) }
            .mapNotNull { p -> Version.read(p.tag.removePrefix(TAG_PREFIX))?.let { it to p } }
            .maxByOrNull { it.first }
            ?: return null
        if (version <= current) return null

        fun file(name: String): PublishedFile? =
            release.assets.firstOrNull { it.name == name && isRepositoryUrl(it.url, repository) }

        return AvailableUpdate(
            version = version,
            notes = release.body.orEmpty().trim(),
            page = release.page.takeIf { it.startsWith("https://github.com/$repository/releases/", ignoreCase = true) }
                ?: "https://github.com/$repository/releases",
            // A release without installer or signature is incomplete; skip it for now.
            installer = file(installerName(version)) ?: return null,
            signature = file(signatureName(version)) ?: return null,
            portable = file(portableName(version)),
        )
    }

    fun isRepositoryUrl(url: String, repository: String): Boolean =
        url.startsWith("https://github.com/$repository/releases/download/", ignoreCase = true) &&
            !url.contains("..") && !url.contains('?') && !url.contains('#')
}
