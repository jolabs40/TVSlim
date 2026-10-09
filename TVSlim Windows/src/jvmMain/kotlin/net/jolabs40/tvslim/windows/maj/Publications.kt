package net.jolabs40.tvslim.windows.maj

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A `major.minor.patch` version, the only form an MSI installer accepts. */
data class Version(val majeure: Int, val mineure: Int, val correctif: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::majeure, Version::mineure, Version::correctif)

    override fun toString(): String = "$majeure.$mineure.$correctif"

    companion object {
        private val FORME = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,5})""")

        fun lire(texte: String): Version? =
            FORME.matchEntire(texte.trim())?.destructured?.let { (majeure, mineure, correctif) ->
                Version(majeure.toInt(), mineure.toInt(), correctif.toInt())
            }
    }
}

/** The subset of a GitHub API release that is used here. */
@Serializable
data class PublicationGithub(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val page: String = "",
    val assets: List<FichierPublie> = emptyList(),
)

@Serializable
data class FichierPublie(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)

data class MiseAJourDisponible(
    val version: Version,
    val notes: String,
    val page: String,
    val installateur: FichierPublie,
    val signature: FichierPublie,
    val portable: FichierPublie?,
)

/**
 * Picks the release that would replace the running version.
 *
 * The repository also publishes the Android app, so only `windows-v*` tags count, excluding drafts and
 * prereleases. Only assets attached to a release of this repository are downloaded; a link in a release
 * description or elsewhere cannot point the download to another file.
 */
object ChoixPublication {

    const val PREFIXE_TAG = "windows-v"

    fun nomInstallateur(version: Version) = "TVSlim-Windows-$version.msi"

    fun nomSignature(version: Version) = "TVSlim-Windows-$version.msi.sig"

    fun nomPortable(version: Version) = "TVSlim-Windows-$version-portable.zip"

    fun choisir(
        publications: List<PublicationGithub>,
        actuelle: Version,
        depot: String,
    ): MiseAJourDisponible? {
        val (version, publication) = publications
            .asSequence()
            .filter { !it.draft && !it.prerelease && it.tag.startsWith(PREFIXE_TAG) }
            .mapNotNull { p -> Version.lire(p.tag.removePrefix(PREFIXE_TAG))?.let { it to p } }
            .maxByOrNull { it.first }
            ?: return null
        if (version <= actuelle) return null

        fun fichier(nom: String): FichierPublie? =
            publication.assets.firstOrNull { it.name == nom && urlDuDepot(it.url, depot) }

        return MiseAJourDisponible(
            version = version,
            notes = publication.body.orEmpty().trim(),
            page = publication.page.takeIf { it.startsWith("https://github.com/$depot/releases/", ignoreCase = true) }
                ?: "https://github.com/$depot/releases",
            // A release without installer or signature is incomplete; skip it for now.
            installateur = fichier(nomInstallateur(version)) ?: return null,
            signature = fichier(nomSignature(version)) ?: return null,
            portable = fichier(nomPortable(version)),
        )
    }

    fun urlDuDepot(url: String, depot: String): Boolean =
        url.startsWith("https://github.com/$depot/releases/download/", ignoreCase = true) &&
            !url.contains("..") && !url.contains('?') && !url.contains('#')
}
