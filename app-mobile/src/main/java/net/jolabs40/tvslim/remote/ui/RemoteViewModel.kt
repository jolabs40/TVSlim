package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
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
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.remote.adb.AppareilDecouvert
import net.jolabs40.tvslim.remote.adb.ClientAdb
import net.jolabs40.tvslim.remote.adb.DecouverteTv
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
    val detectes: List<AppareilDecouvert> = emptyList(),
    val nomsConnus: Map<String, String> = emptyMap(),
    val recherche: String = "",
    val filtre: Filtre = Filtre.TOUS,
    val memoire: RepartitionMemoire = RepartitionMemoire(),
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
    private val decouverte: DecouverteTv,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatRemote())
    val etat: StateFlow<EtatRemote> = _etat.asStateFlow()

    private val lecteur = LecteurDistant(client)
    private var journal: JournalRepository? = null
    private var moteur: MoteurDebloat? = null

    /** Une seule observation de journal à la fois : sinon celui de la TV précédente écrirait encore. */
    private var suiviJournal: Job? = null

    /** Guette l'arrivée d'un launcher que l'on vient d'envoyer installer. */
    private var guet: Job? = null

    /** La découverte mDNS ne tourne que pendant qu'on regarde l'écran de connexion. */
    private var veille: Job? = null

    init {
        viewModelScope.launch {
            client.connexion.collect { connexion -> _etat.update { it.copy(connexion = connexion) } }
        }
        viewModelScope.launch {
            _etat.update {
                it.copy(
                    hoteSaisi = preferences.dernierHote(),
                    portSaisi = preferences.dernierPort().toString(),
                    nomsConnus = preferences.nomsConnus(),
                    catalogue = catalogueRepo.catalogue(),
                )
            }
        }
    }

    /**
     * Cherche les téléviseurs qui s'annoncent sur le réseau. Un appareil dont le débogage
     * réseau est actif publie un service `_adb._tcp` : autant s'en servir plutôt que d'exiger
     * une adresse IP ou un QR code.
     */
    fun chercherAppareils() {
        if (veille?.isActive == true) return
        veille = viewModelScope.launch {
            decouverte.flux().collect { appareils ->
                _etat.update { courant ->
                    courant.copy(
                        // Le nom du cast d'abord, celui retenu d'une visite précédente ensuite.
                        detectes = appareils.map { appareil ->
                            appareil.copy(
                                nomConvivial = appareil.nomConvivial
                                    ?: courant.nomsConnus[appareil.hote],
                            )
                        },
                    )
                }
            }
        }
    }

    fun arreterRecherche() {
        veille?.cancel()
        veille = null
    }

    /** Se connecte à un appareil trouvé sur le réseau, sans rien saisir. */
    fun connecterA(appareil: AppareilDecouvert) {
        _etat.update { it.copy(hoteSaisi = appareil.hote, portSaisi = appareil.port.toString()) }
        connecter()
    }

    fun majHote(valeur: String) = _etat.update { it.copy(hoteSaisi = valeur.trim()) }

    fun majPort(valeur: String) =
        _etat.update { it.copy(portSaisi = valeur.filter { c -> c.isDigit() }) }

    fun majRecherche(valeur: String) = _etat.update { it.copy(recherche = valeur) }

    fun majFiltre(filtre: Filtre) = _etat.update { it.copy(filtre = filtre) }

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

    /**
     * Applique le contenu d'un QR code affiché par le téléviseur, puis se connecte dans la
     * foulée. Trois formes acceptées : l'URI `tvslim://connect?host=…&port=…` que produit
     * l'application du téléviseur, une adresse `hôte:port`, ou une adresse seule.
     */
    fun appliquerScan(valeur: String) {
        val brut = valeur.trim()
        val (hote, port) = when {
            brut.startsWith(SCHEMA) -> {
                val uri = Uri.parse(brut)
                uri.getQueryParameter("host").orEmpty() to
                    (uri.getQueryParameter("port")?.toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)
            }

            brut.count { it == ':' } == 1 ->
                brut.substringBefore(':') to
                    (brut.substringAfter(':').toIntOrNull() ?: PORT_ADB_PAR_DEFAUT)

            else -> brut to PORT_ADB_PAR_DEFAUT
        }
        if (hote.isBlank() || hote.any { it.isWhitespace() }) {
            afficher("Code non reconnu : $brut")
            return
        }
        _etat.update { it.copy(hoteSaisi = hote, portSaisi = port.toString()) }
        connecter()
    }

    fun signalerEchecScan(motif: String) =
        afficher(if (motif.isBlank()) "Lecture annulée." else motif)

    fun deconnecter() {
        client.deconnecter()
        guet?.cancel()
        guet = null
        suiviJournal?.cancel()
        suiviJournal = null
        journal = null
        moteur = null
        _etat.update { it.copy(lignes = emptyList(), infos = InfosAppareil.VIDE, journal = emptyList()) }
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

            // Une seule commande pour tout : sur une liaison réseau, chaque aller-retour se paie.
            val photo = lecteur.photographie(
                paquetsSurveilles = catalogue.entrees.map { it.paquet },
                paquetsDAccueil = paquetsDAccueil,
            )
            val selection = _etat.value.selection.map { it.entree.paquet }.toSet()

            // Le modèle vient d'être lu : on le retient pour nommer l'appareil la prochaine fois.
            val nom = "${photo.infos.marque} ${photo.infos.modele}".trim()
            if (nom.isNotBlank()) preferences.retenirNom(_etat.value.connexion.hote, nom)

            _etat.update { courant ->
                courant.copy(
                    chargement = false,
                    catalogue = catalogue,
                    infos = photo.infos,
                    nomsConnus = courant.nomsConnus + (_etat.value.connexion.hote to nom),
                    lignes = catalogue.entrees.map { entree ->
                        val etatPaquet = photo.etats[entree.paquet] ?: EtatPaquet.ABSENT
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

    // --- Confirmation ---------------------------------------------------------------------

    /** Demande confirmation avant de désactiver : rien ne part tant que ce n'est pas validé. */
    fun demanderApplication() {
        val courant = _etat.value
        val choisies = courant.selection.map { it.entree }
        when {
            choisies.isEmpty() -> afficher("Aucun paquet sélectionné.")
            !courant.connecte -> afficher("Connectez-vous d'abord à un téléviseur.")
            else -> _etat.update { it.copy(confirmation = Confirmation.Application(choisies)) }
        }
    }

    fun demanderRestauration() {
        val aRestaurer = journal?.paquetsADesactivationActive().orEmpty()
        when {
            aRestaurer.isEmpty() -> afficher("Rien à restaurer sur ce téléviseur.")
            !_etat.value.connecte -> afficher("Connectez-vous d'abord à un téléviseur.")
            else -> _etat.update { it.copy(confirmation = Confirmation.Restauration(aRestaurer)) }
        }
    }

    fun annulerConfirmation() = _etat.update { it.copy(confirmation = null) }

    fun confirmer() {
        when (val demande = _etat.value.confirmation) {
            is Confirmation.Application -> appliquer(demande.entrees)
            is Confirmation.Restauration -> reactiver(demande.paquets)
            null -> Unit
        }
        annulerConfirmation()
    }

    // --- Actions --------------------------------------------------------------------------

    private fun appliquer(entrees: List<EntreePaquet>) {
        val courant = _etat.value
        val moteurActif = moteur ?: return
        viewModelScope.launch {
            _etat.update { it.copy(progression = Progression(0, entrees.size)) }
            val resultats = moteurActif.desactiver(
                entrees = entrees,
                catalogue = courant.catalogue,
                etats = courant.lignes.associate { it.entree.paquet to it.etat },
                launchersDisponibles = courant.infos.launchersTiers.isNotEmpty(),
                surProgression = { fait, total ->
                    _etat.update { it.copy(progression = Progression(fait, total)) }
                },
            )
            terminer(resultats.count { it.reussi }, resultats.size, resultats.filterNot { it.reussi })
        }
    }

    fun reactiver(paquets: List<String>) {
        val moteurActif = moteur
        if (paquets.isEmpty() || moteurActif == null) {
            afficher("Rien à restaurer.")
            return
        }
        viewModelScope.launch {
            _etat.update { it.copy(progression = Progression(0, paquets.size)) }
            val resultats = moteurActif.reactiver(paquets) { fait, total ->
                _etat.update { it.copy(progression = Progression(fait, total)) }
            }
            terminer(resultats.count { it.reussi }, resultats.size, resultats.filterNot { it.reussi })
        }
    }

    /** Annule une action précise du journal, sans toucher au reste. */
    fun annulerAction(action: ActionJournal) {
        when (action.type) {
            TypeAction.DESACTIVATION -> reactiver(listOf(action.cible))
            else -> afficher("Cette action ne s'annule pas depuis ici.")
        }
    }

    /**
     * Ouvre la fiche d'un launcher dans la boutique du téléviseur. L'installation elle-même se
     * valide à la télécommande : le compagnon ne pose aucun APK sur l'appareil.
     */
    fun installerLauncher(paquet: String) {
        val moteurActif = moteur
        if (moteurActif == null) {
            afficher("Connectez-vous d'abord à un téléviseur.")
            return
        }
        viewModelScope.launch {
            val resultat = moteurActif.ouvrirFicheBoutique(paquet)
            if (!resultat.reussi) {
                afficher("Impossible d'ouvrir la boutique : ${resultat.message}")
                return@launch
            }
            afficher("Fiche ouverte sur le téléviseur : validez l'installation à la télécommande.")
            guetterInstallation(paquet)
        }
    }

    /**
     * Guette l'arrivée du launcher après avoir ouvert sa fiche, plutôt que d'exiger un
     * « Actualiser » manuel : la personne est devant son téléviseur, pas devant le téléphone.
     * Une question courte toutes les cinq secondes, abandonnée au bout de trois minutes.
     */
    private fun guetterInstallation(paquet: String) {
        guet?.cancel()
        guet = viewModelScope.launch {
            withTimeoutOrNull(DUREE_GUET_MS) {
                while (isActive) {
                    delay(INTERVALLE_GUET_MS)
                    if (!_etat.value.connecte) return@withTimeoutOrNull
                    if (lecteur.estInstalle(paquet)) {
                        rafraichir()
                        afficher("Installé. L'accueil d'usine peut maintenant être remplacé.")
                        return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    /** Lit la répartition de la mémoire. Séparé du rafraîchissement : la commande est lourde. */
    fun rafraichirMemoire() {
        if (!_etat.value.connecte) return
        viewModelScope.launch {
            _etat.update { it.copy(chargement = true) }
            val memoire = lecteur.memoire()
            _etat.update { it.copy(chargement = false, memoire = memoire) }
        }
    }

    /** Arrête les processus d'une application, sans rien changer à son état d'installation. */
    fun forcerArret(paquet: String) {
        val moteurActif = moteur ?: return
        viewModelScope.launch {
            val resultat = moteurActif.forcerArret(paquet)
            afficher(
                if (resultat.reussi) "$paquet arrêté." else "Échec : ${resultat.message}",
            )
            rafraichirMemoire()
        }
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

    private fun terminer(
        succes: Int,
        total: Int,
        echecs: List<net.jolabs40.tvslim.moteur.ResultatAction>,
    ) {
        afficher(
            buildString {
                append("$succes sur $total.")
                echecs.take(MAX_ECHECS).forEach { append("\n${it.nom} : ${it.message}") }
            },
        )
        _etat.update { it.copy(progression = null) }
        toutDeselectionner()
        rafraichir()
    }

    private suspend fun ouvrirJournal(hote: String) {
        suiviJournal?.cancel()
        val fichier = File(File(contexte.filesDir, "journaux"), "${hote.replace('.', '_')}.json")
        val ouvert = JournalRepository(fichier)
        ouvert.charger()
        journal = ouvert
        moteur = MoteurDebloat(client, ouvert)
        suiviJournal = viewModelScope.launch {
            ouvert.actions.collect { actions -> _etat.update { it.copy(journal = actions) } }
        }
    }

    private fun afficher(texte: String) = _etat.update { it.copy(message = texte) }

    private companion object {
        const val MAX_ECHECS = 4
        const val SCHEMA = "tvslim://"
        const val DUREE_GUET_MS = 3 * 60 * 1000L
        const val INTERVALLE_GUET_MS = 5_000L
    }
}
