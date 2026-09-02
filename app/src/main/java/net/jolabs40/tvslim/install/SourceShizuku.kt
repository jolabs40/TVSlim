package net.jolabs40.tvslim.install

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * D'où vient l'APK de Shizuku, et à quoi on reconnaît qu'il est authentique.
 *
 * ## L'empreinte épinglée
 *
 * `EMPREINTE_CERTIFICAT` est le condensat SHA-256 du certificat de signature de Shizuku
 * (`CN=Rikka`), relevé le 2026-09-01 sur la version v13.6.0 publiée par le dépôt officiel,
 * avec `apksigner verify --print-certs`.
 *
 * Ce que cet épinglage garantit, et ce qu'il ne garantit pas :
 *
 * - **Il garantit** que l'APK installé est signé par la même clé que la version officielle de
 *   référence. Un fichier substitué en chemin, une release compromise signée par une autre clé,
 *   un miroir hostile : tous sont refusés.
 * - **Il ne garantit pas** que la clé elle-même soit légitime : elle a été admise sur la foi du
 *   canal (HTTPS, dépôt GitHub officiel du projet) au moment du relevé. C'est une confiance à
 *   la première rencontre, pas une chaîne de confiance.
 *
 * En cas de rotation de clé par l'auteur, l'installation échouera : il faudra relever la
 * nouvelle empreinte et la mettre à jour ici, jamais désactiver le contrôle.
 */
object SourceShizuku {

    const val PAQUET = "moe.shizuku.privileged.api"

    const val EMPREINTE_CERTIFICAT =
        "268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30"

    /** Version de référence sur laquelle l'empreinte a été relevée. */
    const val VERSION_REFERENCE = "v13.6.0"

    const val API_DERNIERE_VERSION =
        "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest"

    /** Hôtes admis pour le téléchargement, redirections comprises. */
    val HOTES_ADMIS = setOf(
        "api.github.com",
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    /** Un APK de plus de 100 Mo n'est pas Shizuku : on refuse avant d'écrire sur le disque. */
    const val TAILLE_MAX_OCTETS = 100L * 1024 * 1024

    const val NOM_FICHIER = "shizuku-telecharge.apk"
}

@Serializable
data class ReleaseGitHub(
    @SerialName("tag_name") val tag: String = "",
    val assets: List<AssetGitHub> = emptyList(),
) {
    /** Le seul APK de la publication ; les autres fichiers (signatures, notes) sont ignorés. */
    fun apk(): AssetGitHub? = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
}

@Serializable
data class AssetGitHub(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
)
