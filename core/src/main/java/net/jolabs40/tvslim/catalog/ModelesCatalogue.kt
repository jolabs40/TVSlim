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

@Serializable
data class Catalogue(
    val version: Int = 0,
    val source: String = "",
    val profils: List<Profil> = emptyList(),
    val categories: List<Categorie> = emptyList(),
    val entrees: List<EntreePaquet> = emptyList(),
    val proteges: List<PaquetProtege> = emptyList(),
    val reglages: List<ReglageSysteme> = emptyList(),
) {
    private val protegesParPaquet: Map<String, PaquetProtege> by lazy {
        proteges.associateBy { it.paquet }
    }

    fun estProtege(paquet: String): Boolean = protegesParPaquet.containsKey(paquet)

    fun motifProtection(paquet: String): String? = protegesParPaquet[paquet]?.raison

    fun nomCategorie(id: String): String = categories.firstOrNull { it.id == id }?.nom ?: id

    fun entreesDuProfil(profil: Profil): List<EntreePaquet> =
        entrees.filter { it.categorie in profil.categories }
}
