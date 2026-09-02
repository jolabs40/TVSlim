package net.jolabs40.tvslim.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.catalog.ReglageSysteme
import net.jolabs40.tvslim.device.AppareilRepository
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.MoteurDebloat
import net.jolabs40.tvslim.install.EtatInstallation
import net.jolabs40.tvslim.install.InstalleurShizuku
import net.jolabs40.tvslim.install.PhaseInstallation
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.privileged.EtatPrivilege
import net.jolabs40.tvslim.privileged.ShizukuPasserelle
import net.jolabs40.tvslim.system.PreferencesRepository
import net.jolabs40.tvslim.system.ReglagesSysteme
import javax.inject.Inject

data class LignePaquet(
    val entree: EntreePaquet,
    val etat: EtatPaquet,
    val selectionne: Boolean = false,
)

data class LigneReglage(
    val reglage: ReglageSysteme,
    val valeurActuelle: String?,
) {
    val optimise: Boolean get() = valeurActuelle == reglage.valeurOptimisee
}

data class EtatUi(
    val chargement: Boolean = true,
    val travailEnCours: Boolean = false,
    val privilege: EtatPrivilege = EtatPrivilege.INCONNU,
    val installation: EtatInstallation = EtatInstallation(),
    val installationDisponible: Boolean = false,
    val sourcesInconnuesAutorisees: Boolean = false,
    val ecritureDirecte: Boolean = false,
    val gardienActif: Boolean = false,
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val catalogue: Catalogue = Catalogue(),
    val lignes: List<LignePaquet> = emptyList(),
    val reglages: List<LigneReglage> = emptyList(),
    val journal: List<ActionJournal> = emptyList(),
    val message: String? = null,
) {
    val selection: List<LignePaquet> get() = lignes.filter { it.selectionne }
    val presentes: List<LignePaquet> get() = lignes.filter { it.etat != EtatPaquet.ABSENT }
    val desactivees: Int get() = lignes.count { it.etat == EtatPaquet.DESACTIVE }
    val privilegesPrets: Boolean get() = privilege == EtatPrivilege.PRET
}

/**
 * État partagé par les quatre écrans. Un seul ViewModel plutôt qu'un par écran : ils lisent
 * tous la même photographie de l'appareil, et la mémoire est comptée sur ces téléviseurs.
 */
@HiltViewModel
class TvSlimViewModel @Inject constructor(
    private val catalogueRepo: CatalogueRepository,
    private val appareil: AppareilRepository,
    private val moteur: MoteurDebloat,
    private val reglagesSysteme: ReglagesSysteme,
    private val journal: JournalRepository,
    private val preferences: PreferencesRepository,
    private val passerelle: ShizukuPasserelle,
    private val installeur: InstalleurShizuku,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatUi())
    val etat: StateFlow<EtatUi> = _etat.asStateFlow()

    init {
        viewModelScope.launch {
            passerelle.etat.collect { privilege -> _etat.update { it.copy(privilege = privilege) } }
        }
        viewModelScope.launch {
            journal.actions.collect { actions -> _etat.update { it.copy(journal = actions) } }
        }
        viewModelScope.launch {
            preferences.gardienActif.collect { actif ->
                _etat.update { it.copy(gardienActif = actif) }
            }
        }
        viewModelScope.launch {
            installeur.etat.collect { installation ->
                _etat.update { it.copy(installation = installation) }
                // Shizuku vient d'être installé : ses privilèges changent d'état.
                if (installation.phase == PhaseInstallation.SUCCES) rafraichir()
            }
        }
        rafraichir()
    }

    fun rafraichir() {
        viewModelScope.launch {
            _etat.update { it.copy(chargement = true) }
            passerelle.rafraichirEtat()
            journal.charger()
            val catalogue = catalogueRepo.catalogue()
            val paquetsDAccueil = catalogue.entrees
                .filter { it.requiertLauncherTiers }
                .map { it.paquet }
                .toSet()
            val infos = appareil.infos(paquetsDAccueil)
            val selectionCourante = _etat.value.lignes
                .filter { it.selectionne }
                .map { it.entree.paquet }
                .toSet()

            val lignes = withContext(Dispatchers.IO) {
                catalogue.entrees.map { entree ->
                    val etat = appareil.etat(entree.paquet)
                    LignePaquet(
                        entree = entree,
                        etat = etat,
                        selectionne = entree.paquet in selectionCourante && etat == EtatPaquet.ACTIF,
                    )
                }
            }
            val reglages = withContext(Dispatchers.IO) {
                catalogue.reglages.map { LigneReglage(it, reglagesSysteme.lire(it)) }
            }

            _etat.update {
                it.copy(
                    chargement = false,
                    catalogue = catalogue,
                    infos = infos,
                    lignes = lignes,
                    reglages = reglages,
                    ecritureDirecte = reglagesSysteme.ecritureDirectePossible(),
                    installationDisponible = installeur.disponible,
                    sourcesInconnuesAutorisees = installeur.sourcesInconnuesAutorisees(),
                )
            }
        }
    }

    /** Télécharge Shizuku, contrôle sa signature, puis laisse le système demander confirmation. */
    fun installerShizuku() {
        viewModelScope.launch { installeur.installer() }
    }

    fun reinitialiserInstallation() = installeur.reinitialiser()

    /** Écran système où autoriser TV Slim à installer des paquets. */
    fun intentionSourcesInconnues(): Intent = installeur.intentionSourcesInconnues()

    fun basculerSelection(paquet: String) {
        _etat.update { courant ->
            courant.copy(
                lignes = courant.lignes.map { ligne ->
                    if (ligne.entree.paquet == paquet && ligne.etat == EtatPaquet.ACTIF) {
                        ligne.copy(selectionne = !ligne.selectionne)
                    } else {
                        ligne
                    }
                },
            )
        }
    }

    /** Coche toutes les entrées encore actives des catégories du profil. */
    fun selectionnerProfil(profil: Profil) {
        _etat.update { courant ->
            courant.copy(
                lignes = courant.lignes.map { ligne ->
                    if (ligne.entree.categorie in profil.categories &&
                        ligne.etat == EtatPaquet.ACTIF
                    ) {
                        ligne.copy(selectionne = true)
                    } else {
                        ligne
                    }
                },
            )
        }
    }

    fun toutDeselectionner() {
        _etat.update { courant ->
            courant.copy(lignes = courant.lignes.map { it.copy(selectionne = false) })
        }
    }

    fun appliquerSelection() {
        val courant = _etat.value
        val choisies = courant.selection.map { it.entree }
        if (choisies.isEmpty()) {
            afficher("Aucun paquet sélectionné.")
            return
        }
        if (!courant.privilegesPrets) {
            afficher("Privilèges indisponibles : voir l'écran Réglages.")
            return
        }
        viewModelScope.launch {
            _etat.update { it.copy(travailEnCours = true) }
            val resultats = moteur.desactiver(choisies, courant.catalogue)
            val succes = resultats.count { it.reussi }
            val echecs = resultats.filterNot { it.reussi }
            afficher(
                buildString {
                    append("$succes désactivé(s) sur ${resultats.size}.")
                    echecs.take(MAX_ECHECS_AFFICHES).forEach { append("\n${it.nom} : ${it.message}") }
                },
            )
            _etat.update { it.copy(travailEnCours = false) }
            toutDeselectionner()
            rafraichir()
        }
    }

    fun reactiver(paquets: List<String>) {
        if (paquets.isEmpty()) {
            afficher("Rien à restaurer.")
            return
        }
        if (!_etat.value.privilegesPrets) {
            afficher("Privilèges indisponibles : voir l'écran Réglages.")
            return
        }
        viewModelScope.launch {
            _etat.update { it.copy(travailEnCours = true) }
            val resultats = moteur.reactiver(paquets)
            afficher("${resultats.count { it.reussi }} paquet(s) réactivé(s) sur ${resultats.size}.")
            _etat.update { it.copy(travailEnCours = false) }
            rafraichir()
        }
    }

    /** Réactive tout ce que l'application a désactivé et remet les réglages d'origine. */
    fun toutRestaurer() {
        viewModelScope.launch {
            val aRestaurer = journal.paquetsADesactivationActive()
            _etat.value.reglages.filter { it.optimise }.forEach { ligne ->
                reglagesSysteme.ecrire(ligne.reglage, ligne.reglage.valeurDefaut)
            }
            preferences.definirGardien(false)
            reactiver(aRestaurer)
        }
    }

    fun basculerReglage(ligne: LigneReglage) {
        viewModelScope.launch {
            val cible = if (ligne.optimise) {
                ligne.reglage.valeurDefaut
            } else {
                ligne.reglage.valeurOptimisee
            }
            val erreur = reglagesSysteme.ecrire(ligne.reglage, cible)
            afficher(erreur ?: "${ligne.reglage.nom} : $cible")
            rafraichir()
        }
    }

    fun definirGardien(actif: Boolean) {
        viewModelScope.launch {
            preferences.definirGardien(actif)
            if (actif && !reglagesSysteme.ecritureDirectePossible()) {
                afficher(
                    "Gardien activé, mais inopérant tant que l'autorisation d'écriture " +
                        "des réglages n'a pas été accordée par ADB.",
                )
            }
        }
    }

    fun exporterJournal() {
        viewModelScope.launch {
            val infos = _etat.value.infos
            val chemin = journal.exporterMarkdown(
                "Appareil : ${infos.marque} ${infos.modele} — Android ${infos.versionAndroid} " +
                    "(${infos.build})",
            )
            afficher("Journal exporté : $chemin")
        }
    }

    fun demanderAutorisation() {
        passerelle.demanderAutorisation()
        passerelle.rafraichirEtat()
    }

    fun effacerMessage() = _etat.update { it.copy(message = null) }

    private fun afficher(texte: String) = _etat.update { it.copy(message = texte) }

    private companion object {
        const val MAX_ECHECS_AFFICHES = 4
    }
}
