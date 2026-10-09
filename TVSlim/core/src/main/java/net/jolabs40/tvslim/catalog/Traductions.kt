package net.jolabs40.tvslim.catalog

import kotlinx.serialization.Serializable

/**
 * Catalogue translations.
 *
 * `catalogue.json` holds the structure and the English text (the default language, like `values/`).
 * Each other language only adds an override file keyed by ID (`catalogue-fr.json`), so adding a
 * package only touches the base file and a missing translation falls back to English.
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

/** Text of a recommended launcher. Its highlights are only replaced as a whole list. */
@Serializable
data class TexteLauncher(
    val nom: String? = null,
    val description: String? = null,
    val pointsForts: List<String>? = null,
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
    val launchers: Map<String, TexteLauncher> = emptyMap(),
)

/** Applies a language override field by field; missing fields keep their original value. */
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
    launchers = launchers.map { launcher ->
        traductions.launchers[launcher.paquet]?.let { texte ->
            launcher.copy(
                nom = texte.nom ?: launcher.nom,
                description = texte.description ?: launcher.description,
                // A list of a different length would mix two languages on the same card.
                pointsForts = texte.pointsForts
                    ?.takeIf { it.size == launcher.pointsForts.size }
                    ?: launcher.pointsForts,
            )
        } ?: launcher
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
