package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.windows.adb.ConnexionUi
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.windows.reseau.AppareilDecouvert
import net.jolabs40.tvslim.windows.reseau.ResultatDecouverte

/**
 * Stable pour Compose, et honnêtement : `EntreePaquet` vient du noyau, qui n'applique pas le
 * compilateur Compose — son inférence de stabilité ne traverse pas la frontière. Sans cette
 * promesse, toutes les lignes de l'écran Paquets se redessinent à chaque avancée de la progression.
 */
@Immutable
data class LignePaquet(
    val entree: EntreePaquet,
    val etat: EtatPaquet,
    val selectionne: Boolean = false,
)

/** Filtre d'affichage de la liste. */
enum class Filtre { TOUS, ACTIFS, DESACTIVES }

/** Ce qu'on s'apprête à faire, soumis à confirmation. */
sealed interface Confirmation {
    /** Désactivation : on montre surtout les effets de bord, connus du catalogue. */
    data class Application(val entrees: List<EntreePaquet>) : Confirmation

    data class Restauration(val paquets: List<String>) : Confirmation

    /** Réinjection d'une configuration sauvegardée : on montre ce qu'elle changera, et seulement cela. */
    data class Reinjection(val plan: PlanReinjection) : Confirmation
}

@Immutable
data class Progression(val fait: Int, val total: Int)

/**
 * Tout ce que la fenêtre affiche. Le pendant exact de `EtatRemote` du compagnon, plus ce que le
 * bureau ajoute : le volet de détail d'un paquet et la recherche sur le réseau.
 *
 * Tenu pour immuable : tous les champs sont des `val`, et aucune liste n'est mutée en place —
 * chaque changement passe par `copy()`.
 */
@Immutable
data class EtatApp(
    val hoteSaisi: String = "",
    val portSaisi: String = PORT_ADB_PAR_DEFAUT.toString(),
    val connexion: ConnexionUi = ConnexionUi(),
    val chargement: Boolean = false,
    val progression: Progression? = null,
    val catalogue: Catalogue = Catalogue(),
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val lignes: List<LignePaquet> = emptyList(),
    val journal: List<ActionJournal> = emptyList(),
    val mesures: HistoriqueMesures = HistoriqueMesures(),
    val decouverte: ResultatDecouverte = ResultatDecouverte(),
    val nomsConnus: Map<String, String> = emptyMap(),
    val recherche: String = "",
    val filtre: Filtre = Filtre.TOUS,
    val paquetDetaille: String? = null,
    val memoire: RepartitionMemoire = RepartitionMemoire(),
    /** Une lecture de la mémoire a abouti ou échoué : « lecture en cours » ne dure pas au-delà. */
    val lectureMemoireTentee: Boolean = false,
    val stockage: RepartitionStockage = RepartitionStockage(),
    /** Comme pour la mémoire : un échec de lecture se dit, au lieu d'un « lecture en cours » sans fin. */
    val lectureStockageTentee: Boolean = false,
    val permissions: EtatPermissions = EtatPermissions(),
    val confirmation: Confirmation? = null,
    val message: MessageUi? = null,
) {
    val connecte: Boolean get() = connexion.etat == EtatConnexion.CONNECTE
    val travailEnCours: Boolean get() = progression != null
    val selection: List<LignePaquet> by lazy { lignes.filter { it.selectionne } }

    // Calculées une fois par état, et non à chaque lecture : l'écran Paquets en consulte
    // plusieurs, et chacune reparcourait toutes les entrées du catalogue.
    private val presentes: List<LignePaquet> by lazy {
        lignes.filter { it.etat != EtatPaquet.ABSENT }
    }

    /** Ce que la liste affiche vraiment, une fois la recherche et le filtre appliqués. */
    val affichees: List<LignePaquet> by lazy {
        presentes
            .filter { ligne ->
                when (filtre) {
                    Filtre.TOUS -> true
                    Filtre.ACTIFS -> ligne.etat == EtatPaquet.ACTIF
                    Filtre.DESACTIVES -> ligne.etat == EtatPaquet.DESACTIVE
                }
            }
            .filter { ligne ->
                recherche.isBlank() ||
                    ligne.entree.nom.contains(recherche, ignoreCase = true) ||
                    ligne.entree.paquet.contains(recherche, ignoreCase = true)
            }
    }

    val nombreActifs: Int by lazy { presentes.count { it.etat == EtatPaquet.ACTIF } }
    val nombreDesactives: Int by lazy { presentes.count { it.etat == EtatPaquet.DESACTIVE } }

    /** Le paquet dont parle le volet de détail, tant qu'il figure encore dans la liste. */
    val ligneDetaillee: LignePaquet? by lazy {
        paquetDetaille?.let { paquet -> affichees.firstOrNull { it.entree.paquet == paquet } }
    }

    /** Les appareils trouvés, nommés : le nom du cast d'abord, celui d'une visite précédente ensuite. */
    val detectes: List<AppareilDecouvert> by lazy {
        decouverte.appareils.map { it.copy(nomConvivial = it.nomConvivial ?: nomsConnus[it.hote]) }
    }
}

// --- Transformations de la sélection ------------------------------------------------------
//
// Choisir des paquets ne demande ni téléviseur ni coroutine : ce sont des fonctions de l'état vers
// l'état, identiques à celles du compagnon, et testées de la même façon.

/** Coche ou décoche un paquet. Un paquet déjà désactivé ou absent ne se sélectionne pas. */
fun EtatApp.avecBascule(paquet: String): EtatApp = copy(
    lignes = lignes.map { ligne ->
        if (ligne.entree.paquet == paquet && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = !ligne.selectionne)
        } else {
            ligne
        }
    },
)

/** Coche tout ce qu'un profil couvre, sans jamais décocher ce qui l'était déjà. */
fun EtatApp.avecProfil(profil: Profil): EtatApp = copy(
    lignes = lignes.map { ligne ->
        if (ligne.entree.categorie in profil.categories && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = true)
        } else {
            ligne
        }
    },
)

fun EtatApp.sansSelection(): EtatApp =
    copy(lignes = lignes.map { it.copy(selectionne = false) })
