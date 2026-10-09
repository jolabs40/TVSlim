package net.jolabs40.tvslim.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Risk level shown next to each package. */
@Serializable
enum class Risque {
    @SerialName("aucun")
    AUCUN,

    @SerialName("faible")
    FAIBLE,

    @SerialName("moyen")
    MOYEN,

    @SerialName("eleve")
    ELEVE,
}

@Serializable
data class EntreePaquet(
    val paquet: String,
    val nom: String,
    val description: String,
    val categorie: String,
    val risque: Risque = Risque.FAIBLE,
    val marque: String = "",
    @SerialName("effetDeBord") val effetDeBord: String? = null,
    @SerialName("tailleMo") val tailleMo: Int? = null,
    /** Order within a batch; lower values run first. */
    val ordre: Int = 100,
    /** True for home screen packages, which require a third-party launcher to be installed. */
    val requiertLauncherTiers: Boolean = false,
    /**
     * False for an entry described from a submitted inventory, never disabled on a real device: it can be
     * disabled individually but is never checked by a profile.
     */
    val eprouve: Boolean = true,
)

@Serializable
data class Categorie(
    val id: String,
    val nom: String,
)

@Serializable
data class Profil(
    val id: String,
    val nom: String,
    val description: String,
    val categories: List<String> = emptyList(),
)

@Serializable
data class PaquetProtege(
    val paquet: String,
    val raison: String,
)

@Serializable
data class ReglageSysteme(
    val cle: String,
    val portee: String,
    val nom: String,
    val description: String,
    val valeurOptimisee: String,
    val valeurDefaut: String,
    val reappliquerAuDemarrage: Boolean = false,
)

/**
 * Replacement launcher suggested by TV Slim. Without a third-party launcher the safeguard refuses
 * to disable the stock home screen; this entry offers the way out.
 */
@Serializable
data class LauncherRecommande(
    val paquet: String,
    val nom: String,
    val description: String,
    /** Stable ID that each app maps to its logo. */
    val id: String = "",
    /** Other packages of the same launcher, such as its debug build. */
    val variantes: List<String> = emptyList(),
    val pointsForts: List<String> = emptyList(),
    /**
     * False until the launcher is on the Play Store: with no store page to open on the TV, the
     * button says so instead of failing.
     */
    val disponible: Boolean = true,
    /** Website, offered while the launcher is not installed. */
    val site: String = "",
) {
    fun correspond(paquetInstalle: String): Boolean =
        paquetInstalle == paquet || paquetInstalle in variantes

    /** Website without the scheme, e.g. `startlightlauncher.com`. */
    val siteAffiche: String get() = site.substringAfter("://").trimEnd('/')
}

/** A widespread third-party launcher: named and shown with its logo when installed, never suggested. */
@Serializable
data class LauncherConnu(
    val id: String,
    val nom: String,
    val paquets: List<String>,
)

@Serializable
data class Catalogue(
    val version: Int = 0,
    val source: String = "",
    val profils: List<Profil> = emptyList(),
    val categories: List<Categorie> = emptyList(),
    val entrees: List<EntreePaquet> = emptyList(),
    val proteges: List<PaquetProtege> = emptyList(),
    val reglages: List<ReglageSysteme> = emptyList(),
    val launchers: List<LauncherRecommande> = emptyList(),
    val launchersConnus: List<LauncherConnu> = emptyList(),
) {
    private val protegesParPaquet: Map<String, PaquetProtege> by lazy {
        proteges.associateBy { it.paquet }
    }

    fun estProtege(paquet: String): Boolean = protegesParPaquet.containsKey(paquet)

    fun motifProtection(paquet: String): String? = protegesParPaquet[paquet]?.raison

    fun nomCategorie(id: String): String = categories.firstOrNull { it.id == id }?.nom ?: id

    /** Entries in the profile's categories, tested ones only. */
    fun entreesDuProfil(profil: Profil): List<EntreePaquet> =
        entrees.filter { it.categorie in profil.categories && it.eprouve }

    /** The recommended launcher that [paquet] is a variant of, if any. */
    fun launcherRecommande(paquet: String): LauncherRecommande? =
        launchers.firstOrNull { it.correspond(paquet) }

    /** Brand name of an installed launcher, when known. */
    fun nomLauncher(paquet: String): String? =
        launcherRecommande(paquet)?.nom ?: launchersConnus.firstOrNull { paquet in it.paquets }?.nom

    /** Logo ID of an installed launcher, when there is one. */
    fun idLauncher(paquet: String): String? =
        launcherRecommande(paquet)?.id?.takeIf { it.isNotBlank() }
            ?: launchersConnus.firstOrNull { paquet in it.paquets }?.id

    /** Recommended launchers still worth suggesting: none of their variants (debug build included) is installed. */
    fun launchersAProposer(installes: Collection<String>): List<LauncherRecommande> =
        launchers.filterNot { recommande -> installes.any(recommande::correspond) }

    /**
     * Sorts installed launchers with TV Slim's recommendations first (release before debug build), then
     * the others in their original order.
     */
    fun <T> recommandesDAbord(installes: List<T>, paquet: (T) -> String): List<T> =
        installes.sortedBy { element ->
            val recommande = launcherRecommande(paquet(element))
            when {
                recommande == null -> 2
                recommande.paquet == paquet(element) -> 0
                else -> 1
            }
        }
}
