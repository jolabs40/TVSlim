package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.configuration.planDeDerive
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.PaquetInconnu
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.windows.adb.ConnexionUi
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.windows.reseau.AppareilDecouvert
import net.jolabs40.tvslim.windows.reseau.ResultatDecouverte

/**
 * `EntreePaquet` comes from the core, which is not built with the Compose compiler, so its stability is not
 * inferred. Without `@Immutable` every Packages row recomposes on each progress update.
 */
@Immutable
data class LignePaquet(
    val entree: EntreePaquet,
    val etat: EtatPaquet,
    val selectionne: Boolean = false,
)

enum class Filtre { TOUS, ACTIFS, DESACTIVES }

/** An action waiting for the user's confirmation. */
sealed interface Confirmation {
    /** Disabling packages; the dialog stresses the side effects listed in the catalogue. */
    data class Application(val entrees: List<EntreePaquet>) : Confirmation

    data class Restauration(val paquets: List<String>) : Confirmation

    /**
     * Reapplying a saved configuration, showing only what it would change. With [derive], the plan comes
     * from the journal (`planDeDerive`) instead of a file.
     */
    data class Reinjection(val plan: PlanReinjection, val derive: Boolean = false) : Confirmation

    data class Installation(val apk: ApkChoisi) : Confirmation

    data object Redemarrage : Confirmation
}

@Immutable
data class Progression(val fait: Int, val total: Int)

/**
 * Window state: the companion's `EtatRemote` plus the package detail pane and network discovery.
 *
 * Safe to mark immutable: every field is a `val` and lists are only replaced through `copy()`.
 */
@Immutable
data class EtatApp(
    val hoteSaisi: String = "",
    val portSaisi: String = PORT_ADB_PAR_DEFAUT.toString(),
    val connexion: ConnexionUi = ConnexionUi(),
    /** The TV is rebooting; we wait for it to come back and reconnect. */
    val redemarrage: Boolean = false,
    val chargement: Boolean = false,
    val progression: Progression? = null,
    val catalogue: Catalogue = Catalogue(),
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val lignes: List<LignePaquet> = emptyList(),
    /** Preinstalled packages missing from the catalogue: shown, never offered for removal. */
    val inconnus: List<PaquetInconnu> = emptyList(),
    val journal: List<ActionJournal> = emptyList(),
    val mesures: HistoriqueMesures = HistoriqueMesures(),
    val decouverte: ResultatDecouverte = ResultatDecouverte(),
    val nomsConnus: Map<String, String> = emptyMap(),
    val recherche: String = "",
    val filtre: Filtre = Filtre.TOUS,
    val paquetDetaille: String? = null,
    val memoire: RepartitionMemoire = RepartitionMemoire(),
    /** Set once a memory read has succeeded or failed, so the "reading" state cannot last forever. */
    val lectureMemoireTentee: Boolean = false,
    /** A memory read is running, started by the tab or prefetched on connection. */
    val memoireEnLecture: Boolean = false,
    val stockage: RepartitionStockage = RepartitionStockage(),
    /** Same as for memory: a failed read is reported instead of showing "reading" forever. */
    val lectureStockageTentee: Boolean = false,
    val stockageEnLecture: Boolean = false,
    val permissions: EtatPermissions = EtatPermissions(),
    val installation: EtatInstallation = EtatInstallation(),
    val commande: EtatCommande = EtatCommande(),
    val confirmation: Confirmation? = null,
    val message: MessageUi? = null,
) {
    val connecte: Boolean get() = connexion.etat == EtatConnexion.CONNECTE
    val travailEnCours: Boolean get() = progression != null
    val selection: List<LignePaquet> by lazy { lignes.filter { it.selectionne } }

    // Lazy, computed once per state: the Packages screen reads several of these and each one scans
    // the whole catalogue.
    private val presentes: List<LignePaquet> by lazy {
        lignes.filter { it.etat != EtatPaquet.ABSENT }
    }

    /** Rows left once the search and filter are applied. */
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

    /** Unknown packages under the same filter and search as catalogue rows. */
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

    /** Package in the detail pane, as long as it is still listed. */
    val ligneDetaillee: LignePaquet? by lazy {
        paquetDetaille?.let { paquet -> affichees.firstOrNull { it.entree.paquet == paquet } }
    }

    /** Discovered devices, named by their cast name, or else by the name seen on a previous visit. */
    val detectes: List<AppareilDecouvert> by lazy {
        decouverte.appareils.map { it.copy(nomConvivial = it.nomConvivial ?: nomsConnus[it.hote]) }
    }

    /**
     * What TV Slim disabled and the TV re-enabled on its own, usually after a system update (see
     * `planDeDerive`). Null while loading or applying, since a half-applied state would look like drift.
     */
    val derive: PlanReinjection? by lazy {
        if (!connecte || chargement || travailEnCours || lignes.isEmpty()) return@lazy null
        catalogue.planDeDerive(journal, lignes.associate { it.entree.paquet to it.etat }, infos)
    }
}

// --- Selection ------------------------------------------------------------------------------
//
// Pure state-to-state functions, identical to the companion's and tested the same way.

/** Toggles a package. Disabled or absent packages cannot be selected. */
fun EtatApp.avecBascule(paquet: String): EtatApp = copy(
    lignes = lignes.map { ligne ->
        if (ligne.entree.paquet == paquet && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = !ligne.selectionne)
        } else {
            ligne
        }
    },
)

/**
 * Selects everything a profile covers, never unselecting anything. Untested entries are never covered:
 * they must be ticked by hand.
 */
fun EtatApp.avecProfil(profil: Profil): EtatApp = if (!infos.typeAppareil.pourLeCatalogue) this else copy(
    lignes = lignes.map { ligne ->
        val couverte = ligne.entree.categorie in profil.categories && ligne.entree.eprouve
        if (couverte && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = true)
        } else {
            ligne
        }
    },
)

fun EtatApp.sansSelection(): EtatApp =
    copy(lignes = lignes.map { it.copy(selectionne = false) })
