package net.jolabs40.tvslim.remote.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.remote.adb.ClientAdb
import net.jolabs40.tvslim.remote.adb.ConnexionUi
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.remote.data.PreferencesRemote
import java.io.File
import javax.inject.Inject

data class LignePaquet(
    val entree: EntreePaquet,
    val etat: EtatPaquet,
    val selectionne: Boolean = false,
)

data class EtatRemote(
    val hoteSaisi: String = "",
    val portSaisi: String = PORT_ADB_PAR_DEFAUT.toString(),
    val connexion: ConnexionUi = ConnexionUi(),
    val chargement: Boolean = false,
    val travailEnCours: Boolean = false,
    val catalogue: Catalogue = Catalogue(),
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val lignes: List<LignePaquet> = emptyList(),
    val journal: List<ActionJournal> = emptyList(),
    val message: String? = null,
) {
    val connecte: Boolean get() = connexion.etat == EtatConnexion.CONNECTE
    val selection: List<LignePaquet> get() = lignes.filter { it.selectionne }
    val presentes: List<LignePaquet> get() = lignes.filter { it.etat != EtatPaquet.ABSENT }
}

/**
 * Pilote du compagnon : une connexion ADB, un catalogue, un journal par téléviseur.
 *
 * Le moteur de débloat vient du noyau partagé et ne sait pas d'où viennent ses privilèges —
 * ici, d'une session ADB ouverte depuis le téléphone.
 */
@HiltViewModel
class RemoteViewModel @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val client: ClientAdb,
    private val catalogueRepo: CatalogueRepository,
    private val preferences: PreferencesRemote,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatRemote())
    val etat: StateFlow<EtatRemote> = _etat.asStateFlow()

    private val lecteur = LecteurDistant(client)
    private var journal: JournalRepository? = null
    private var moteur: MoteurDebloat? = null

    init {
        viewModelScope.launch {
            client.connexion.collect { connexion -> _etat.update { it.copy(connexion = connexion) } }
        }
        viewModelScope.launch {
            _etat.update {
                it.copy(
                    hoteSaisi = preferences.dernierHote(),
                    portSaisi = preferences.dernierPort().toString(),
                    catalogue = catalogueRepo.catalogue(),
                )
            }
        }
    }

    fun majHote(valeur: String) = _etat.update { it.copy(hoteSaisi = valeur.trim()) }

    fun majPort(valeur: String) = _etat.update { it.copy(portSaisi = valeur.filter { c -> c.isDigit() }) }

    fun connecter() {
        val courant = _etat.value
        val hote = courant.hoteSaisi
        val port = courant.portSaisi.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT
        if (hote.isBlank()) {
            afficher("Renseignez l'adresse du téléviseur.")
            return
        }
        viewModelScope.launch {
            if (client.connecter(hote, port)) {
                preferences.retenir(hote, port)
                ouvrirJournal(hote)
                rafraichir()
            }
        }
    }

    fun deconnecter() {
        client.deconnecter()
        _etat.update { it.copy(lignes = emptyList(), infos = InfosAppareil.VIDE) }
    }

    fun rafraichir() {
        if (!_etat.value.connecte) return
        viewModelScope.launch {
            _etat.update { it.copy(chargement = true) }
            val catalogue = catalogueRepo.catalogue()
            val paquetsDAccueil = catalogue.entrees
                .filter { it.requiertLauncherTiers }
                .map { it.paquet }
                .toSet()
            val etats = lecteur.etats(catalogue.entrees.map { it.paquet })
            val infos = lecteur.infos(paquetsDAccueil)
            val selection = _etat.value.selection.map { it.entree.paquet }.toSet()

            _etat.update { courant ->
                courant.copy(
                    chargement = false,
                    catalogue = catalogue,
                    infos = infos,
                    lignes = catalogue.entrees.map { entree ->
                        val etatPaquet = etats[entree.paquet] ?: EtatPaquet.ABSENT
                        LignePaquet(
                            entree = entree,
                            etat = etatPaquet,
                            selectionne = entree.paquet in selection &&
                                etatPaquet == EtatPaquet.ACTIF,
                        )
                    },
                )
            }
        }
    }

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

    fun toutDeselectionner() =
        _etat.update { c -> c.copy(lignes = c.lignes.map { it.copy(selectionne = false) }) }

    fun appliquerSelection() {
        val courant = _etat.value
        val choisies = courant.selection.map { it.entree }
        val moteurActif = moteur
        if (choisies.isEmpty()) {
            afficher("Aucun paquet sélectionné.")
            return
        }
        if (!courant.connecte || moteurActif == null) {
            afficher("Connectez-vous d'abord à un téléviseur.")
            return
        }
        viewModelScope.launch {
            _etat.update { it.copy(travailEnCours = true) }
            val etats = courant.lignes.associate { it.entree.paquet to it.etat }
            val resultats = moteurActif.desactiver(
                entrees = choisies,
                catalogue = courant.catalogue,
                etats = etats,
                launchersDisponibles = courant.infos.launchersTiers.isNotEmpty(),
            )
            val echecs = resultats.filterNot { it.reussi }
            afficher(
                buildString {
                    append("${resultats.count { it.reussi }} désactivé(s) sur ${resultats.size}.")
                    echecs.take(MAX_ECHECS).forEach { append("\n${it.nom} : ${it.message}") }
                },
            )
            _etat.update { it.copy(travailEnCours = false) }
            toutDeselectionner()
            rafraichir()
        }
    }

    fun reactiver(paquets: List<String>) {
        val moteurActif = moteur
        if (paquets.isEmpty() || moteurActif == null) {
            afficher("Rien à restaurer.")
            return
        }
        viewModelScope.launch {
            _etat.update { it.copy(travailEnCours = true) }
            val resultats = moteurActif.reactiver(paquets)
            afficher("${resultats.count { it.reussi }} paquet(s) réactivé(s) sur ${resultats.size}.")
            _etat.update { it.copy(travailEnCours = false) }
            rafraichir()
        }
    }

    /** Réactive tout ce que le compagnon a désactivé sur ce téléviseur. */
    fun toutRestaurer() {
        val actif = journal ?: return
        reactiver(actif.paquetsADesactivationActive())
    }

    fun exporterJournal() {
        val actif = journal ?: return
        viewModelScope.launch {
            val infos = _etat.value.infos
            val dossier = contexte.getExternalFilesDir(null) ?: contexte.filesDir
            val nom = "TVSlim-${_etat.value.connexion.hote.replace('.', '-')}.md"
            val chemin = actif.exporterMarkdown(
                cible = File(dossier, nom),
                entete = "Appareil : ${infos.marque} ${infos.modele} — Android " +
                    "${infos.versionAndroid} (${infos.build})",
            )
            afficher("Journal exporté : $chemin")
        }
    }

    fun effacerMessage() = _etat.update { it.copy(message = null) }

    private suspend fun ouvrirJournal(hote: String) {
        val fichier = File(File(contexte.filesDir, "journaux"), "${hote.replace('.', '_')}.json")
        val ouvert = JournalRepository(fichier)
        ouvert.charger()
        journal = ouvert
        moteur = MoteurDebloat(client, ouvert)
        viewModelScope.launch {
            ouvert.actions.collect { actions -> _etat.update { it.copy(journal = actions) } }
        }
    }

    private fun afficher(texte: String) = _etat.update { it.copy(message = texte) }

    private companion object {
        const val MAX_ECHECS = 4
    }
}
