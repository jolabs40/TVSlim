package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.PaquetInconnu
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.remote.adb.AppareilDecouvert
import net.jolabs40.tvslim.remote.adb.ConnexionUi
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT

/**
 * Stable pour Compose, et honnêtement : `EntreePaquet` vient du noyau, qui n'applique pas le
 * compilateur Compose — son inférence de stabilité ne traverse donc pas la frontière de module,
 * et toute la liste passait pour instable. Sans cette promesse, les cinquante-six lignes de
 * l'écran Paquets se redessinent à chaque avancée de la barre de progression.
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
    /** Désactivation : on montre surtout les effets de bord, connus mais jamais affichés avant. */
    data class Application(val entrees: List<EntreePaquet>) : Confirmation

    data class Restauration(val paquets: List<String>) : Confirmation

    /** Réinjection d'une configuration sauvegardée : on montre ce qu'elle changera, et seulement cela. */
    data class Reinjection(val plan: PlanReinjection) : Confirmation

    /** Installation d'un APK : l'application qui arrive, sa version, et ce qu'elle remplace. */
    data class Installation(val apk: ApkChoisi) : Confirmation
}

@Immutable
data class Progression(val fait: Int, val total: Int)

/**
 * Tenu pour immuable : tous les champs sont des `val`, et aucune des listes n'est jamais mutée
 * en place — chaque changement passe par `copy()`. La promesse est donc tenue, et elle permet
 * aux écrans de sauter une recomposition quand ce qui les concerne n'a pas bougé.
 */
@Immutable
data class EtatRemote(
    val hoteSaisi: String = "",
    val portSaisi: String = PORT_ADB_PAR_DEFAUT.toString(),
    val connexion: ConnexionUi = ConnexionUi(),
    val chargement: Boolean = false,
    val progression: Progression? = null,
    val catalogue: Catalogue = Catalogue(),
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val lignes: List<LignePaquet> = emptyList(),
    /** Les paquets livrés avec le téléviseur que le catalogue ne décrit pas : montrés, jamais proposés. */
    val inconnus: List<PaquetInconnu> = emptyList(),
    val journal: List<ActionJournal> = emptyList(),
    val mesures: HistoriqueMesures = HistoriqueMesures(),
    val detectes: List<AppareilDecouvert> = emptyList(),
    val nomsConnus: Map<String, String> = emptyMap(),
    val recherche: String = "",
    val filtre: Filtre = Filtre.TOUS,
    val memoire: RepartitionMemoire = RepartitionMemoire(),
    val stockage: RepartitionStockage = RepartitionStockage(),
    val permissions: EtatPermissions = EtatPermissions(),
    val installation: EtatInstallation = EtatInstallation(),
    val commande: EtatCommande = EtatCommande(),
    val confirmation: Confirmation? = null,
    val message: String? = null,
) {
    val connecte: Boolean get() = connexion.etat == EtatConnexion.CONNECTE
    val travailEnCours: Boolean get() = progression != null
    val selection: List<LignePaquet> by lazy { lignes.filter { it.selectionne } }

    // Calculées une fois par état, et non à chaque lecture : l'écran Paquets en consulte
    // cinq — `affichees`, `nombreActifs`, `nombreDesactives`, `selection` — et chacune
    // reparcourait les quatre-vingt-seize entrées.
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

    /** Les inconnus que la liste montre, sous le même filtre et la même recherche que le catalogue. */
    val inconnusAffiches: List<PaquetInconnu> by lazy {
        inconnus
            .filter { inconnu ->
                when (filtre) {
                    Filtre.TOUS -> true
                    Filtre.ACTIFS -> inconnu.etat == EtatPaquet.ACTIF
                    Filtre.DESACTIVES -> inconnu.etat == EtatPaquet.DESACTIVE
                }
            }
            .filter { recherche.isBlank() || it.paquet.contains(recherche, ignoreCase = true) }
    }

    val nombreActifs: Int by lazy { presentes.count { it.etat == EtatPaquet.ACTIF } }
    val nombreDesactives: Int by lazy { presentes.count { it.etat == EtatPaquet.DESACTIVE } }
}

// --- Transformations de la sélection ------------------------------------------------------
//
// Choisir des paquets ne demande ni téléviseur ni coroutine : ce sont des fonctions de l'état
// vers l'état, et elles se lisent — et se testent — mieux ici que noyées dans le pilote.

/** Coche ou décoche un paquet. Un paquet déjà désactivé ou absent ne se sélectionne pas. */
fun EtatRemote.avecBascule(paquet: String): EtatRemote = copy(
    lignes = lignes.map { ligne ->
        if (ligne.entree.paquet == paquet && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = !ligne.selectionne)
        } else {
            ligne
        }
    },
)

/** Coche tout ce qu'un profil couvre, sans jamais décocher ce qui l'était déjà. */
fun EtatRemote.avecProfil(profil: Profil): EtatRemote = copy(
    lignes = lignes.map { ligne ->
        if (ligne.entree.categorie in profil.categories && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = true)
        } else {
            ligne
        }
    },
)

fun EtatRemote.sansSelection(): EtatRemote =
    copy(lignes = lignes.map { it.copy(selectionne = false) })
