package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.remote.adb.AppareilDecouvert
import net.jolabs40.tvslim.remote.adb.ConnexionUi
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT

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
}

data class Progression(val fait: Int, val total: Int)

data class EtatRemote(
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
    val detectes: List<AppareilDecouvert> = emptyList(),
    val nomsConnus: Map<String, String> = emptyMap(),
    val recherche: String = "",
    val filtre: Filtre = Filtre.TOUS,
    val memoire: RepartitionMemoire = RepartitionMemoire(),
    val permissions: EtatPermissions = EtatPermissions(),
    val confirmation: Confirmation? = null,
    val message: String? = null,
) {
    val connecte: Boolean get() = connexion.etat == EtatConnexion.CONNECTE
    val travailEnCours: Boolean get() = progression != null
    val selection: List<LignePaquet> get() = lignes.filter { it.selectionne }

    private val presentes: List<LignePaquet> get() = lignes.filter { it.etat != EtatPaquet.ABSENT }

    /** Ce que la liste affiche vraiment, une fois la recherche et le filtre appliqués. */
    val affichees: List<LignePaquet>
        get() = presentes
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

    val nombreActifs: Int get() = presentes.count { it.etat == EtatPaquet.ACTIF }
    val nombreDesactives: Int get() = presentes.count { it.etat == EtatPaquet.DESACTIVE }
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
