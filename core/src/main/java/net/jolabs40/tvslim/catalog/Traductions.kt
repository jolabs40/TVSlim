package net.jolabs40.tvslim.catalog

import kotlinx.serialization.Serializable

/**
 * Traductions du catalogue.
 *
 * `catalogue.json` porte la structure et les textes anglais — la langue par défaut du projet,
 * comme `values/` pour les ressources. Chaque langue supplémentaire n'apporte qu'un fichier de
 * **surcharge** (`catalogue-fr.json`), indexé par identifiant : ajouter un paquet ne demande
 * donc de toucher qu'au fichier de base, et une traduction manquante retombe sur l'anglais au
 * lieu de laisser un trou.
 */
@Serializable
data class TexteEntree(
    val nom: String? = null,
    val description: String? = null,
    val effetDeBord: String? = null,
)

@Serializable
data class TexteNomme(
    val nom: String? = null,
    val description: String? = null,
)

@Serializable
data class Traductions(
    val langue: String = "",
    val source: String? = null,
    val categories: Map<String, String> = emptyMap(),
    val profils: Map<String, TexteNomme> = emptyMap(),
    val entrees: Map<String, TexteEntree> = emptyMap(),
    val proteges: Map<String, String> = emptyMap(),
    val reglages: Map<String, TexteNomme> = emptyMap(),
)

/** Applique une surcharge de langue, champ par champ. Ce qui manque garde sa valeur d'origine. */
fun Catalogue.traduit(traductions: Traductions): Catalogue = copy(
    source = traductions.source ?: source,
    categories = categories.map { categorie ->
        traductions.categories[categorie.id]?.let { categorie.copy(nom = it) } ?: categorie
    },
    profils = profils.map { profil ->
        traductions.profils[profil.id]?.let { texte ->
            profil.copy(
                nom = texte.nom ?: profil.nom,
                description = texte.description ?: profil.description,
            )
        } ?: profil
    },
    entrees = entrees.map { entree ->
        traductions.entrees[entree.paquet]?.let { texte ->
            entree.copy(
                nom = texte.nom ?: entree.nom,
                description = texte.description ?: entree.description,
                effetDeBord = texte.effetDeBord ?: entree.effetDeBord,
            )
        } ?: entree
    },
    proteges = proteges.map { protege ->
        traductions.proteges[protege.paquet]?.let { protege.copy(raison = it) } ?: protege
    },
    reglages = reglages.map { reglage ->
        traductions.reglages[reglage.cle]?.let { texte ->
            reglage.copy(
                nom = texte.nom ?: reglage.nom,
                description = texte.description ?: reglage.description,
            )
        } ?: reglage
    },
)
