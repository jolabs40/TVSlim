package net.jolabs40.tvslim.ui

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
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.ReglageSysteme
import net.jolabs40.tvslim.device.AppareilRepository
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.reseau.InfosReseau
import net.jolabs40.tvslim.reseau.PointDeContact
import net.jolabs40.tvslim.system.PreferencesRepository
import net.jolabs40.tvslim.system.ReglagesSysteme
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

data class EtatUi(
    val chargement: Boolean = true,
    val ecritureDirecte: Boolean = false,
    val gardienActif: Boolean = false,
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val contact: PointDeContact = PointDeContact(),
    val reglages: List<LigneReglage> = emptyList(),
    val paquets: List<LignePaquetTv> = emptyList(),
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
            _etat.update {
                it.copy(
                    chargement = false,
                    infos = infos,
                    contact = contact,
                    reglages = reglages,
                    paquets = paquets,
                    ecritureDirecte = reglagesSysteme.ecritureDirectePossible(),
                )
            }
        }
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
