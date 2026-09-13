package net.jolabs40.tvslim.windows.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.mesure.Mesure
import net.jolabs40.tvslim.mesure.MesuresRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.data.PreferencesWindows
import net.jolabs40.tvslim.windows.reseau.AppareilDecouvert
import net.jolabs40.tvslim.windows.reseau.DecouverteTv
import net.jolabs40.tvslim.windows.reseau.cleDeFichier
import net.jolabs40.tvslim.windows.reseau.interpreterSaisie
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.msg_baseline_reset
import net.jolabs40.tvslim.windows.ressources.msg_connect_first
import net.jolabs40.tvslim.windows.ressources.msg_enter_address
import net.jolabs40.tvslim.windows.ressources.msg_failure
import net.jolabs40.tvslim.windows.ressources.msg_journal_export_failed
import net.jolabs40.tvslim.windows.ressources.msg_journal_exported
import net.jolabs40.tvslim.windows.ressources.msg_launcher_installed
import net.jolabs40.tvslim.windows.ressources.msg_not_undoable
import net.jolabs40.tvslim.windows.ressources.msg_nothing_selected
import net.jolabs40.tvslim.windows.ressources.msg_nothing_to_restore
import net.jolabs40.tvslim.windows.ressources.msg_stopped
import net.jolabs40.tvslim.windows.ressources.msg_store_failed
import net.jolabs40.tvslim.windows.ressources.msg_store_opened
import net.jolabs40.tvslim.windows.ressources.msg_wireless_unsupported
import java.io.File

/**
 * Pilote de la fenêtre : une connexion ADB, un catalogue, un journal par téléviseur.
 *
 * Le pendant de `RemoteViewModel` du compagnon, décision pour décision. Le moteur de débloat vient
 * du noyau partagé et ignore d'où viennent ses privilèges — ici, d'une session ADB ouverte depuis
 * l'ordinateur.
 */
class PiloteApp(
    private val client: ClientAdb,
    private val catalogueRepo: CatalogueRepository,
    private val preferences: PreferencesWindows,
    private val decouverte: DecouverteTv,
    private val emplacements: Emplacements,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatApp())
    val etat: StateFlow<EtatApp> = _etat.asStateFlow()

    private val lecteur = LecteurDistant(client)

    private var journal: JournalRepository? = null
    private var mesures: MesuresRepository? = null
    private var moteur: MoteurDebloat? = null

    /** Les permissions privilégiées ont leur propre pilote : leur état n'a rien à voir avec le débloat. */
    val permissions = PilotePermissions(
        lecteur = lecteur,
        moteur = { moteur },
        portee = viewModelScope,
        afficher = ::afficher,
    )

    /** Une seule observation de journal à la fois : sinon celui de la TV précédente écrirait encore. */
    private var suiviJournal: Job? = null

    /** Une reconnexion silencieuse à la fois. */
    private var reprise: Job? = null

    /** Guette l'arrivée d'un launcher que l'on vient d'envoyer installer. */
    private var guet: Job? = null

    /** La recherche sur le réseau ne tourne que pendant qu'on regarde l'écran de connexion. */
    private var veille: Job? = null

    /** Vrai après « Se déconnecter », jusqu'à la prochaine connexion demandée. */
    private var deconnexionVolontaire = false

    init {
        viewModelScope.launch {
            client.connexion.collect { connexion -> _etat.update { it.copy(connexion = connexion) } }
        }
        viewModelScope.launch {
            permissions.etat.collect { lues -> _etat.update { it.copy(permissions = lues) } }
        }
        viewModelScope.launch {
            // Les lectures d'abord, la mise à jour ensuite : `update` rejoue son bloc quand l'un des
            // `collect` ci-dessus a écrit entre-temps, et une lecture disque rejouée est perdue.
            val lues = preferences.lire()
            val catalogue = catalogueRepo.catalogue()
            _etat.update {
                it.copy(
                    hoteSaisi = it.hoteSaisi.ifBlank { lues.dernierHote },
                    portSaisi = if (it.hoteSaisi.isBlank()) lues.dernierPort.toString() else it.portSaisi,
                    nomsConnus = lues.nomsConnus,
                    catalogue = catalogue,
                )
            }
        }
    }

    // --- Connexion ------------------------------------------------------------------------

    fun chercherAppareils() {
        if (veille?.isActive == true) return
        veille = viewModelScope.launch {
            decouverte.flux().collect { resultat -> _etat.update { it.copy(decouverte = resultat) } }
        }
    }

    fun arreterRecherche() {
        veille?.cancel()
        veille = null
    }

    /** Se connecte à un appareil trouvé sur le réseau, sans rien saisir. */
    fun connecterA(appareil: AppareilDecouvert) {
        if (appareil.sansFil) {
            afficher(texte(Res.string.msg_wireless_unsupported))
            return
        }
        _etat.update { it.copy(hoteSaisi = appareil.hote, portSaisi = appareil.port.toString()) }
        connecter()
    }

    fun majHote(valeur: String) = _etat.update { it.copy(hoteSaisi = valeur) }

    fun majPort(valeur: String) =
        _etat.update { it.copy(portSaisi = valeur.filter { c -> c.isDigit() }.take(5)) }

    fun connecter() {
        val courant = _etat.value
        val adresse = interpreterSaisie(courant.hoteSaisi, courant.portSaisi)
        if (adresse == null) {
            afficher(texte(Res.string.msg_enter_address))
            return
        }
        // « 192.168.1.20:5555 » collé d'un bloc se range dans ses deux champs.
        _etat.update { it.copy(hoteSaisi = adresse.hote, portSaisi = adresse.port.toString()) }
        deconnexionVolontaire = false
        reprise?.cancel()
        viewModelScope.launch {
            if (client.connecter(adresse.hote, adresse.port)) {
                preferences.retenir(adresse.hote, adresse.port)
                ouvrirJournal(adresse.hote)
                rafraichir()
            }
        }
    }

    /**
     * Retente le dernier téléviseur joint au retour sur la fenêtre, sans le demander. Une session
     * ADB ne survit pas à la veille du téléviseur ; en cas d'échec — téléviseur éteint, le cas le
     * plus banal — rien ne s'affiche : la personne n'a rien demandé.
     *
     * Jamais après « Se déconnecter » : c'est un choix, et sur un bureau la fenêtre reprend le
     * focus à chaque clic — la session se serait rouverte dans le dos à la première occasion.
     * Jamais non plus vers une adresse seulement tapée : on ne reprend que ce qui a déjà abouti.
     */
    fun reprendreConnexion() {
        if (deconnexionVolontaire || _etat.value.connecte || reprise?.isActive == true) return
        if (_etat.value.connexion.etat == EtatConnexion.CONNEXION) return
        reprise = viewModelScope.launch {
            val lues = preferences.lire()
            if (lues.dernierHote.isBlank()) return@launch
            if (client.connecter(lues.dernierHote, lues.dernierPort, discret = true)) {
                ouvrirJournal(lues.dernierHote)
                rafraichir()
            }
        }
    }

    fun deconnecter() {
        deconnexionVolontaire = true
        client.deconnecter()
        listOf(guet, reprise, suiviJournal).forEach { it?.cancel() }
        guet = null
        reprise = null
        suiviJournal = null
        journal = null
        mesures = null
        moteur = null
        permissions.oublier()
        _etat.update {
            it.copy(
                lignes = emptyList(),
                infos = InfosAppareil.VIDE,
                journal = emptyList(),
                mesures = HistoriqueMesures(),
                // La mémoire lue appartient au téléviseur quitté : la garder la ferait passer pour
                // celle du suivant, que l'onglet ne relirait pas.
                memoire = RepartitionMemoire(),
                lectureMemoireTentee = false,
                paquetDetaille = null,
            )
        }
    }

    fun rafraichir() {
        if (!_etat.value.connecte) return
        viewModelScope.launch {
            _etat.update { it.copy(chargement = true) }
            val catalogue = catalogueRepo.catalogue()
            val paquetsDAccueil = catalogue.entrees.filter { it.requiertLauncherTiers }.map { it.paquet }.toSet()

            // Une seule commande pour tout : sur une liaison réseau, chaque aller-retour se paie.
            val photo = lecteur.photographie(
                paquetsSurveilles = catalogue.entrees.map { it.paquet },
                paquetsDAccueil = paquetsDAccueil,
            )
            val selection = _etat.value.selection.map { it.entree.paquet }.toSet()

            // Le modèle vient d'être lu : on le retient pour nommer l'appareil la prochaine fois.
            val nom = photo.infos.nomAffiche
            val hote = _etat.value.connexion.hote
            if (nom.isNotBlank()) preferences.retenirNom(hote, nom)

            // Chaque photographie sert aussi de mesure : la première fait référence.
            mesures?.enregistrer(
                Mesure(
                    horodatage = System.currentTimeMillis(),
                    paquetsActifs = photo.infos.paquetsInstalles,
                    paquetsDesactives = photo.infos.paquetsDesactives,
                    memoireTotaleMo = photo.infos.memoireTotaleMo,
                    memoireLibreMo = photo.infos.memoireLibreMo,
                ),
            )

            _etat.update { courant ->
                courant.copy(
                    chargement = false,
                    catalogue = catalogue,
                    infos = photo.infos,
                    nomsConnus = if (nom.isBlank()) courant.nomsConnus else courant.nomsConnus + (hote to nom),
                    lignes = catalogue.entrees.map { entree ->
                        val etatPaquet = photo.etats[entree.paquet] ?: EtatPaquet.ABSENT
                        LignePaquet(
                            entree = entree,
                            etat = etatPaquet,
                            selectionne = entree.paquet in selection && etatPaquet == EtatPaquet.ACTIF,
                        )
                    },
                )
            }
        }
    }

    // --- Liste des paquets ----------------------------------------------------------------

    fun majRecherche(valeur: String) = _etat.update { it.copy(recherche = valeur) }

    fun majFiltre(filtre: Filtre) = _etat.update { it.copy(filtre = filtre) }

    fun detailler(paquet: String) = _etat.update { it.copy(paquetDetaille = paquet) }

    fun basculerSelection(paquet: String) = _etat.update { it.avecBascule(paquet) }

    fun selectionnerProfil(profil: Profil) = _etat.update { it.avecProfil(profil) }

    fun toutDeselectionner() = _etat.update { it.sansSelection() }

    // --- Confirmation ---------------------------------------------------------------------

    /** Demande confirmation avant de désactiver : rien ne part tant que ce n'est pas validé. */
    fun demanderApplication() {
        val courant = _etat.value
        val choisies = courant.selection.map { it.entree }
        when {
            choisies.isEmpty() -> afficher(texte(Res.string.msg_nothing_selected))
            !courant.connecte -> afficher(texte(Res.string.msg_connect_first))
            else -> _etat.update { it.copy(confirmation = Confirmation.Application(choisies)) }
        }
    }

    fun demanderRestauration() {
        val aRestaurer = journal?.paquetsADesactivationActive().orEmpty()
        when {
            aRestaurer.isEmpty() -> afficher(texte(Res.string.msg_nothing_to_restore))
            !_etat.value.connecte -> afficher(texte(Res.string.msg_connect_first))
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
            terminer(resultats)
        }
    }

    fun reactiver(paquets: List<String>) {
        val moteurActif = moteur
        if (paquets.isEmpty() || moteurActif == null) {
            afficher(texte(Res.string.msg_nothing_to_restore))
            return
        }
        viewModelScope.launch {
            _etat.update { it.copy(progression = Progression(0, paquets.size)) }
            val resultats = moteurActif.reactiver(paquets) { fait, total ->
                _etat.update { it.copy(progression = Progression(fait, total)) }
            }
            terminer(resultats)
        }
    }

    /** Annule une action précise du journal, sans toucher au reste. */
    fun annulerAction(action: ActionJournal) {
        when (action.type) {
            TypeAction.DESACTIVATION -> reactiver(listOf(action.cible))
            TypeAction.PERMISSION, TypeAction.APP_OP -> permissions.annuler(action)
            else -> afficher(texte(Res.string.msg_not_undoable))
        }
    }

    /**
     * Ouvre la fiche d'un launcher dans la boutique du téléviseur. L'installation se valide à la
     * télécommande : l'application ne pose aucun APK sur l'appareil.
     */
    fun installerLauncher(paquet: String) {
        val moteurActif = moteur ?: return afficher(texte(Res.string.msg_connect_first))
        viewModelScope.launch {
            val resultat = moteurActif.ouvrirFicheBoutique(paquet)
            if (!resultat.reussi) {
                afficher(texte(Res.string.msg_store_failed, resultat.message))
                return@launch
            }
            afficher(texte(Res.string.msg_store_opened))
            guetterInstallation(paquet)
        }
    }

    /**
     * Guette l'arrivée du launcher plutôt que d'exiger un « Actualiser » : la personne est devant
     * son téléviseur, pas devant l'écran. Une question courte toutes les cinq secondes, trois minutes.
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
                        afficher(texte(Res.string.msg_launcher_installed))
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
            _etat.update { it.copy(chargement = false, memoire = memoire, lectureMemoireTentee = true) }
        }
    }

    /** Arrête les processus d'une application, sans rien changer à son état d'installation. */
    fun forcerArret(paquet: String) {
        val moteurActif = moteur ?: return
        viewModelScope.launch {
            val resultat = moteurActif.forcerArret(paquet)
            afficher(
                if (resultat.reussi) {
                    texte(Res.string.msg_stopped, paquet)
                } else {
                    texte(Res.string.msg_failure, resultat.message)
                },
            )
            rafraichirMemoire()
        }
    }

    /** Nom proposé par la fenêtre d'enregistrement. */
    fun nomExportJournal(): String =
        "TVSlim-${cleDeFichier(_etat.value.connexion.hote.ifBlank { "televiseur" })}.md"

    fun exporterJournal(cible: File) {
        val actif = journal ?: return afficher(texte(Res.string.msg_connect_first))
        viewModelScope.launch {
            val infos = _etat.value.infos
            runCatching {
                actif.exporterMarkdown(
                    cible = cible,
                    entete = "Appareil : ${infos.marque} ${infos.modele} — Android " +
                        "${infos.versionAndroid} (${infos.build})",
                )
            }
                .onSuccess { chemin -> afficher(texte(Res.string.msg_journal_exported, chemin)) }
                .onFailure { afficher(texte(Res.string.msg_journal_export_failed, it.message.orEmpty())) }
        }
    }

    /** Repart d'une page blanche : l'état actuel devient le « avant ». */
    fun redefinirReference() {
        val actives = mesures ?: return
        viewModelScope.launch {
            actives.redefinirReference()
            afficher(texte(Res.string.msg_baseline_reset))
        }
    }

    fun effacerMessage() = _etat.update { it.copy(message = null) }

    private fun terminer(resultats: List<ResultatAction>) {
        val echecs = resultats.filterNot { it.reussi }
        afficher(
            MessageUi.Bilan(
                succes = resultats.count { it.reussi },
                total = resultats.size,
                echecs = echecs.take(MAX_ECHECS).map { it.nom to it.message },
            ),
        )
        _etat.update { it.copy(progression = null) }
        toutDeselectionner()
        rafraichir()
    }

    private suspend fun ouvrirJournal(hote: String) {
        suiviJournal?.cancel()
        val cle = cleDeFichier(hote)
        val ouvert = JournalRepository(File(emplacements.journaux, "$cle.json"))
        ouvert.charger()
        journal = ouvert
        moteur = MoteurDebloat(client, ouvert)

        val relevees = MesuresRepository(File(emplacements.mesures, "$cle.json"))
        relevees.charger()
        mesures = relevees

        _etat.update {
            it.copy(memoire = RepartitionMemoire(), lectureMemoireTentee = false, paquetDetaille = null)
        }
        suiviJournal = viewModelScope.launch {
            launch { ouvert.actions.collect { actions -> _etat.update { it.copy(journal = actions) } } }
            launch { relevees.historique.collect { h -> _etat.update { it.copy(mesures = h) } } }
        }
    }

    private fun afficher(message: MessageUi) = _etat.update { it.copy(message = message) }

    private companion object {
        const val MAX_ECHECS = 4
        const val DUREE_GUET_MS = 3 * 60 * 1000L
        const val INTERVALLE_GUET_MS = 5_000L
    }
}
