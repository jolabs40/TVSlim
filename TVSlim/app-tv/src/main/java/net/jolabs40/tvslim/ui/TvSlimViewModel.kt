package net.jolabs40.tvslim.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.ReglageSysteme
import net.jolabs40.tvslim.device.AppareilRepository
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.reseau.InfosReseau
import net.jolabs40.tvslim.reseau.PointDeContact
import net.jolabs40.tvslim.system.GardienDerive
import net.jolabs40.tvslim.system.PreferencesRepository
import net.jolabs40.tvslim.system.ReglagesSysteme
import net.jolabs40.tvslim.system.accueilsUsine
import net.jolabs40.tvslim.system.nomDuLauncher
import javax.inject.Inject

/** Une entrée du catalogue et son état sur ce téléviseur. Lecture seule. */
data class LignePaquetTv(
    val entree: EntreePaquet,
    val etat: EtatPaquet,
)

data class LigneReglage(
    val reglage: ReglageSysteme,
    val valeurActuelle: String?,
) {
    val optimise: Boolean get() = valeurActuelle == reglage.valeurOptimisee
}

/** La dérive, nommée pour l'écran : noms du catalogue plutôt que paquets. */
data class DeriveAffichee(
    val rallumes: List<String>,
    val accueilPerdu: String?,
)

data class EtatUi(
    val chargement: Boolean = true,
    val ecritureDirecte: Boolean = false,
    val gardienActif: Boolean = false,
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val contact: PointDeContact = PointDeContact(),
    val reglages: List<LigneReglage> = emptyList(),
    val paquets: List<LignePaquetTv> = emptyList(),
    /** Ce que la dernière mise à jour système a défait et que rien n'a repris — cf. `GardienDerive`. */
    val derive: DeriveAffichee? = null,
    val message: String? = null,
) {
    /** Entrées du catalogue réellement installées ici : les autres n'ont rien à montrer. */
    val paquetsPresents: List<LignePaquetTv>
        get() = paquets.filter { it.etat != EtatPaquet.ABSENT }

    val paquetsDesactives: Int get() = paquets.count { it.etat == EtatPaquet.DESACTIVE }
}

/**
 * État partagé par les deux écrans. Depuis que le débloat se pilote depuis le compagnon
 * mobile, cette application ne fait plus que deux choses : montrer l'état du téléviseur et
 * tenir les réglages système qui doivent survivre aux redémarrages.
 */
@HiltViewModel
class TvSlimViewModel @Inject constructor(
    private val catalogueRepo: CatalogueRepository,
    private val appareil: AppareilRepository,
    private val reglagesSysteme: ReglagesSysteme,
    private val infosReseau: InfosReseau,
    private val preferences: PreferencesRepository,
    private val gardienDerive: GardienDerive,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatUi())
    val etat: StateFlow<EtatUi> = _etat.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.gardienActif.collect { actif ->
                _etat.update { it.copy(gardienActif = actif) }
            }
        }
        rafraichir()
    }

    fun rafraichir() {
        viewModelScope.launch {
            _etat.update { it.copy(chargement = true) }
            val catalogue = catalogueRepo.catalogue()
            val paquetsDAccueil = catalogue.entrees
                .filter { it.requiertLauncherTiers }
                .map { it.paquet }
                .toSet()
            val infos = appareil.infos(paquetsDAccueil)
            val reglages = withContext(Dispatchers.IO) {
                catalogue.reglages.map { LigneReglage(it, reglagesSysteme.lire(it)) }
            }
            val paquets = withContext(Dispatchers.IO) {
                catalogue.entrees.map { LignePaquetTv(it, appareil.etat(it.paquet)) }
            }
            val contact = withContext(Dispatchers.IO) { infosReseau.pointDeContact() }
            val derive = deriveRestante(catalogue, paquets, infos.accueilActuel)
            _etat.update {
                it.copy(
                    chargement = false,
                    infos = infos,
                    contact = contact,
                    reglages = reglages,
                    paquets = paquets,
                    derive = derive,
                    ecritureDirecte = reglagesSysteme.ecritureDirectePossible(),
                )
            }
        }
    }

    /**
     * Ce qui reste de la dernière dérive, le téléviseur relu : ce que le téléphone ou le PC a repris
     * depuis ne compte plus, et un rapport entièrement repris s'efface.
     */
    private suspend fun deriveRestante(
        catalogue: Catalogue,
        paquets: List<LignePaquetTv>,
        accueil: String,
    ): DeriveAffichee? {
        val stockee = preferences.derive.first() ?: return null
        val actifs = paquets.filter { it.etat == EtatPaquet.ACTIF }.map { it.entree.paquet }.toSet()
        val restante = stockee.restant(actifs, accueil, catalogue.accueilsUsine())
        if (restante != stockee) preferences.retenirDerive(restante)
        if (restante.vide) return null
        val noms = paquets.associate { it.entree.paquet to it.entree.nom }
        return DeriveAffichee(
            rallumes = restante.rallumes.map { noms[it] ?: it },
            accueilPerdu = restante.accueilPerdu?.let(catalogue::nomDuLauncher),
        )
    }

    fun basculerReglage(ligne: LigneReglage) {
        viewModelScope.launch {
            val cible = if (ligne.optimise) {
                ligne.reglage.valeurDefaut
            } else {
                ligne.reglage.valeurOptimisee
            }
            val erreur = withContext(Dispatchers.IO) { reglagesSysteme.ecrire(ligne.reglage, cible) }
            afficher(erreur ?: "${ligne.reglage.nom} : $cible")
            rafraichir()
        }
    }

    fun definirGardien(actif: Boolean) {
        viewModelScope.launch {
            preferences.definirGardien(actif)
            // La première photo, tout de suite : sans elle, la première mise à jour passerait inaperçue.
            if (actif) withContext(Dispatchers.IO) { runCatching { gardienDerive.verifier() } }
            if (actif && !reglagesSysteme.ecritureDirectePossible()) {
                afficher(
                    "Gardien activé, mais inopérant tant que l'autorisation d'écriture " +
                        "des réglages n'a pas été accordée par ADB.",
                )
            }
        }
    }

    fun effacerMessage() = _etat.update { it.copy(message = null) }

    private fun afficher(texte: String) = _etat.update { it.copy(message = texte) }
}
