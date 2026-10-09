package net.jolabs40.tvslim.remote.ui

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
import net.jolabs40.tvslim.remote.adb.AppareilDecouvert
import net.jolabs40.tvslim.remote.adb.ConnexionUi
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT

/**
 * Marked stable by hand: `EntreePaquet` comes from the core, which is not built with the Compose compiler, so
 * stability inference does not cross the module boundary. Without this, every row of the Packages screen
 * recomposes on each progress update.
 */
@Immutable
data class LignePaquet(
    val entree: EntreePaquet,
    val etat: EtatPaquet,
    val selectionne: Boolean = false,
)

enum class Filtre { TOUS, ACTIFS, DESACTIVES }

/** A pending action awaiting user confirmation. */
sealed interface Confirmation {
    /** Disabling packages; the dialog lists their known side effects. */
    data class Application(val entrees: List<EntreePaquet>) : Confirmation

    data class Restauration(val paquets: List<String>) : Confirmation

    /**
     * Re-applying a saved configuration; only what it will change is shown.
     * With [derive], the plan comes from the log rather than a file (see `planDeDerive`).
     */
    data class Reinjection(val plan: PlanReinjection, val derive: Boolean = false) : Confirmation

    /** Installing an APK: the incoming app, its version, and what it replaces. */
    data class Installation(val apk: ApkChoisi) : Confirmation

    /** Rebooting the TV: what it interrupts and what may not come back. */
    data object Redemarrage : Confirmation
}

@Immutable
data class Progression(val fait: Int, val total: Int)

/**
 * `@Immutable` holds: every field is a `val` and no list is ever mutated in place (changes go through `copy()`).
 * Lets screens skip recomposition when their part has not changed.
 */
@Immutable
data class EtatRemote(
    val hoteSaisi: String = "",
    val portSaisi: String = PORT_ADB_PAR_DEFAUT.toString(),
    val connexion: ConnexionUi = ConnexionUi(),
    /** The TV is rebooting; waiting for it to come back to reconnect. */
    val redemarrage: Boolean = false,
    val chargement: Boolean = false,
    val progression: Progression? = null,
    val catalogue: Catalogue = Catalogue(),
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val lignes: List<LignePaquet> = emptyList(),
    /** Preinstalled packages missing from the catalogue: shown, never offered for disabling. */
    val inconnus: List<PaquetInconnu> = emptyList(),
    val journal: List<ActionJournal> = emptyList(),
    val mesures: HistoriqueMesures = HistoriqueMesures(),
    val detectes: List<AppareilDecouvert> = emptyList(),
    val nomsConnus: Map<String, String> = emptyMap(),
    val recherche: String = "",
    val filtre: Filtre = Filtre.TOUS,
    val memoire: RepartitionMemoire = RepartitionMemoire(),
    /** A memory read is running, started by the tab or prefetched on connect. */
    val memoireEnLecture: Boolean = false,
    val stockage: RepartitionStockage = RepartitionStockage(),
    val stockageEnLecture: Boolean = false,
    val permissions: EtatPermissions = EtatPermissions(),
    val installation: EtatInstallation = EtatInstallation(),
    val commande: EtatCommande = EtatCommande(),
    val shizuku: EtatShizuku = EtatShizuku(),
    val confirmation: Confirmation? = null,
    val message: String? = null,
) {
    val connecte: Boolean get() = connexion.etat == EtatConnexion.CONNECTE
    val travailEnCours: Boolean get() = progression != null
    val selection: List<LignePaquet> by lazy { lignes.filter { it.selectionne } }

    // Lazy, computed once per state: the Packages screen reads several of these derived lists, and each one
    // would otherwise walk the whole catalogue again.
    private val presentes: List<LignePaquet> by lazy {
        lignes.filter { it.etat != EtatPaquet.ABSENT }
    }

    /** Rows shown after search and filter. */
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

    /** Unknown packages shown, with the same filter and search as the catalogue. */
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

    /**
     * Packages TV Slim disabled that the TV re-enabled on its own, usually after a system update
     * (see `planDeDerive`). Null while reading or working: a half-applied state would look like drift.
     */
    val derive: PlanReinjection? by lazy {
        if (!connecte || chargement || travailEnCours || lignes.isEmpty()) return@lazy null
        catalogue.planDeDerive(journal, lignes.associate { it.entree.paquet to it.etat }, infos)
    }
}

// --- Selection transforms ----------------------------------------------------------------
//
// Pure state-to-state functions, kept out of the view model so they are easy to test.

/** Toggles a package. Disabled or missing packages cannot be selected. */
fun EtatRemote.avecBascule(paquet: String): EtatRemote = copy(
    lignes = lignes.map { ligne ->
        if (ligne.entree.paquet == paquet && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = !ligne.selectionne)
        } else {
            ligne
        }
    },
)

/**
 * Selects everything a profile covers, never deselecting anything. Untested entries are never covered by a
 * profile; they must be selected by hand.
 */
fun EtatRemote.avecProfil(profil: Profil): EtatRemote = if (!infos.typeAppareil.pourLeCatalogue) this else copy(
    lignes = lignes.map { ligne ->
        val couverte = ligne.entree.categorie in profil.categories && ligne.entree.eprouve
        if (couverte && ligne.etat == EtatPaquet.ACTIF) {
            ligne.copy(selectionne = true)
        } else {
            ligne
        }
    },
)

fun EtatRemote.sansSelection(): EtatRemote =
    copy(lignes = lignes.map { it.copy(selectionne = false) })
