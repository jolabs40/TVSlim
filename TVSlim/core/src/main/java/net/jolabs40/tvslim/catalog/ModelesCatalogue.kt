package net.jolabs40.tvslim.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Niveau de risque affiché à côté de chaque paquet. */
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
    /** Ordre d'application dans un lot : les valeurs les plus faibles passent en premier. */
    val ordre: Int = 100,
    /** Vrai pour les paquets d'accueil, qui exigent qu'un launcher tiers soit installé. */
    val requiertLauncherTiers: Boolean = false,
    /**
     * Faux pour une entrée décrite d'après un inventaire envoyé, sans qu'on ait vu sur un appareil ce que
     * coûte sa désactivation : montrée et désactivable une à une, jamais cochée par un profil.
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
 * L'écran d'accueil de remplacement que TV Slim propose. Sans launcher tiers, le garde-fou refuse
 * de désactiver l'accueil d'usine — et il a raison : cette fiche est l'issue qu'on lui donne.
 */
@Serializable
data class LauncherRecommande(
    val paquet: String,
    val nom: String,
    val description: String,
    /** Identifiant stable, que chaque application associe à son logo. */
    val id: String = "",
    /** Les autres paquets du même launcher, sa version de développement par exemple. */
    val variantes: List<String> = emptyList(),
    val pointsForts: List<String> = emptyList(),
    /**
     * Faux tant que le launcher n'est pas sur le Play Store : il n'y a alors aucune fiche à ouvrir
     * sur le téléviseur, et le bouton le dit plutôt que d'échouer.
     */
    val disponible: Boolean = true,
) {
    fun correspond(paquetInstalle: String): Boolean =
        paquetInstalle == paquet || paquetInstalle in variantes
}

/**
 * Un launcher tiers répandu : on sait le nommer et le montrer à son logo quand il est installé.
 * Il n'est jamais proposé, seulement reconnu.
 */
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

    /** Ce qu'un profil couvre : ses catégories, et seulement ce qu'on a éprouvé. */
    fun entreesDuProfil(profil: Profil): List<EntreePaquet> =
        entrees.filter { it.categorie in profil.categories && it.eprouve }

    /** Le launcher recommandé dont [paquet] est une version, s'il y en a un. */
    fun launcherRecommande(paquet: String): LauncherRecommande? =
        launchers.firstOrNull { it.correspond(paquet) }

    /** Nom commercial d'un launcher installé, quand on le connaît. */
    fun nomLauncher(paquet: String): String? =
        launcherRecommande(paquet)?.nom ?: launchersConnus.firstOrNull { paquet in it.paquets }?.nom

    /** Identifiant du logo d'un launcher installé, quand on en a un. */
    fun idLauncher(paquet: String): String? =
        launcherRecommande(paquet)?.id?.takeIf { it.isNotBlank() }
            ?: launchersConnus.firstOrNull { paquet in it.paquets }?.id

    /**
     * Ce qu'il reste à proposer : un launcher recommandé ne l'est plus dès que l'une de ses
     * versions est installée — la version de développement compte.
     */
    fun launchersAProposer(installes: Collection<String>): List<LauncherRecommande> =
        launchers.filterNot { recommande -> installes.any(recommande::correspond) }
}
