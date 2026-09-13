package net.jolabs40.tvslim.windows.maj

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Une version « majeure.mineure.correctif » : la seule forme qu'accepte un installateur MSI. */
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

/** Une publication telle que la décrit l'API de GitHub — réduite à ce qui sert ici. */
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
 * Choisit, parmi les publications du dépôt, celle qui remplacerait la version en cours.
 *
 * Le dépôt publie aussi l'application Android : seules comptent les publications dont le tag
 * commence par `windows-v`, ni brouillons ni préversions. Et un fichier ne se télécharge que s'il
 * est attaché à une publication **de ce dépôt** — une description de publication, un lien glissé
 * ailleurs, ne peuvent pas faire télécharger autre chose.
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
            // Sans installateur ou sans signature, la publication est incomplète : on attend.
            installateur = fichier(nomInstallateur(version)) ?: return null,
            signature = fichier(nomSignature(version)) ?: return null,
            portable = fichier(nomPortable(version)),
        )
    }

    fun urlDuDepot(url: String, depot: String): Boolean =
        url.startsWith("https://github.com/$depot/releases/download/", ignoreCase = true) &&
            !url.contains("..") && !url.contains('?') && !url.contains('#')
}
