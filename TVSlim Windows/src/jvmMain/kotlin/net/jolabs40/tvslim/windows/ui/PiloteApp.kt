package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.windows.ressources.msg_reboot_not_back
import net.jolabs40.tvslim.windows.ressources.msg_reboot_back
import net.jolabs40.tvslim.device.Redemarrage
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.applications.CacheApplicationsFichiers
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.catalog.avecApplicationsDuMenu
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.device.paquetsInconnus
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.mesure.Mesure
import net.jolabs40.tvslim.mesure.MesuresRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.soutien.InvitationSoutien
import net.jolabs40.tvslim.soutien.PiloteSoutien
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
import net.jolabs40.tvslim.windows.ressources.msg_not_undoable
import net.jolabs40.tvslim.windows.ressources.msg_nothing_selected
import net.jolabs40.tvslim.windows.ressources.msg_nothing_to_restore
import net.jolabs40.tvslim.windows.ressources.msg_stopped
import net.jolabs40.tvslim.windows.ressources.msg_wireless_unsupported
import java.io.File

/**
 * Window controller: one ADB connection, one catalogue, one journal per TV.
 *
 * Mirrors the companion's `RemoteViewModel` decision for decision. The debloat engine comes from the
 * shared core and does not care where its privileges come from (here, an ADB session opened from the PC).
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
    private var installationApk: InstallationApk? = null

    /** Privileged permissions have their own controller: their state is unrelated to debloating. */
    val permissions = PilotePermissions(
        lecteur = lecteur,
        moteur = { moteur },
        portee = viewModelScope,
        afficher = ::afficher,
    )

    /** Support banner, shown after a successful debloat, transfer or install. */
    val soutien = PiloteSoutien(preferences, viewModelScope)

    /** Home screen card (launcher info, install watch) and saved configurations. Shares only state and engine. */
    val configuration = PiloteConfiguration(
        lecteur = lecteur,
        moteur = { moteur },
        installation = { installationApk },
        console = ConsoleAdb(client) { journal },
        etat = { _etat.value },
        majEtat = { transformation -> _etat.update { transformation(it) } },
        portee = viewModelScope,
        afficher = ::afficher,
        rafraichir = ::rafraichir,
        terminer = ::terminer,
        remercier = soutien::remercier,
    )

    /** Applications tab: names and icons read by the helper app, cached on disk. Tracks the connection itself. */
    val applications = PiloteApplications(
        client = client,
        cache = CacheApplicationsFichiers(emplacements.icones),
        portee = viewModelScope,
        afficher = ::afficher,
        etatApp = { _etat.value },
        moteur = { moteur },
        journal = { journal },
        rafraichirPaquets = ::rafraichir,
        remercier = soutien::remercier,
    )

    /** The TV Slim app on the TV, installed from GitHub. */
    val applicationTv = PiloteApplicationTv(
        client = client,
        lecteur = lecteur,
        moteur = { moteur },
        installation = { installationApk },
        dossier = File(emplacements.local, "application-tv"),
        portee = viewModelScope,
        afficher = ::afficher,
        remercier = soutien::remercier,
    )

    /** Files tab. Tracks the connection itself and forgets what it read when the TV changes. */
    val fichiers = PiloteFichiers(client, viewModelScope, ::afficher, soutien::remercier)

    /** One journal subscription at a time, or the previous TV's journal would keep writing. */
    private var suiviJournal: Job? = null

    /** One silent reconnection at a time. */
    private var reprise: Job? = null

    /** Waits for a rebooted TV to come back. */
    private var redemarrage: Job? = null

    /** Connection-time prefetch and the second session it runs on, one at a time. */
    private var prechargement: Job? = null
    private var sessionSeconde: ClientAdb? = null

    /** Network discovery only runs while the connection screen is shown. */
    private var veille: Job? = null

    /** Set by "Disconnect", cleared by the next connection the user asks for. */
    private var deconnexionVolontaire = false

    init {
        viewModelScope.launch {
            client.connexion.collect { connexion -> _etat.update { it.copy(connexion = connexion) } }
        }
        viewModelScope.launch {
            permissions.etat.collect { lues -> _etat.update { it.copy(permissions = lues) } }
        }
        viewModelScope.launch {
            // Read before `update`: its block reruns whenever one of the collectors above writes in
            // between, and disk reads have no place in a block that may run twice.
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

    // --- Connection -----------------------------------------------------------------------

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

    /** Connects to a device found by discovery. */
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
        // "192.168.1.20:5555" pasted in one go is split into both fields.
        _etat.update { it.copy(hoteSaisi = adresse.hote, portSaisi = adresse.port.toString()) }
        deconnexionVolontaire = false
        reprise?.cancel()
        viewModelScope.launch {
            if (client.connecter(adresse.hote, adresse.port)) {
                preferences.retenir(adresse.hote, adresse.port)
                ouvrirJournal(adresse.hote)
                rafraichir()
                precharger()
            }
        }
    }

    /**
     * Silently retries the last TV reached when the window is restored, since ADB sessions do not survive
     * TV standby. A failure (usually the TV is off) shows nothing: the user did not ask for anything.
     *
     * Never after "Disconnect": on a desktop the window regains focus on every click, so the session would
     * reopen behind the user's back. Never to an address that was only typed: only a past success is resumed.
     */
    fun reprendreConnexion() {
        // During a reboot, the reboot watcher reconnects; do not race it.
        if (deconnexionVolontaire || _etat.value.connecte || reprise?.isActive == true || _etat.value.redemarrage) return
        if (_etat.value.connexion.etat == EtatConnexion.CONNEXION) return
        reprise = viewModelScope.launch {
            val lues = preferences.lire()
            if (lues.dernierHote.isBlank()) return@launch
            if (client.connecter(lues.dernierHote, lues.dernierPort, discret = true)) {
                ouvrirJournal(lues.dernierHote)
                rafraichir()
                precharger()
            }
        }
    }

    fun deconnecter() {
        deconnexionVolontaire = true
        arreterPrechargement()
        client.deconnecter()
        listOf(reprise, suiviJournal).forEach { it?.cancel() }
        configuration.oublier()
        reprise = null
        suiviJournal = null
        journal = null
        mesures = null
        moteur = null
        installationApk = null
        permissions.oublier()
        applicationTv.oublier()
        _etat.update {
            it.copy(
                lignes = emptyList(),
                inconnus = emptyList(),
                infos = InfosAppareil.VIDE,
                journal = emptyList(),
                mesures = HistoriqueMesures(),
                // Otherwise this TV's memory reading would pass for the next TV's, and the tab would
                // not re-read it.
                memoire = RepartitionMemoire(),
                lectureMemoireTentee = false,
                stockage = RepartitionStockage(),
                lectureStockageTentee = false,
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

            // A single command for everything: each round trip is costly over the network.
            val photo = lecteur.photographie(
                paquetsSurveilles = catalogue.entrees.map { it.paquet },
                paquetsDAccueil = paquetsDAccueil,
            )
            val selection = _etat.value.selection.map { it.entree.paquet }.toSet()

            // Remember the model name to label the device next time.
            val nom = photo.infos.nomAffiche
            val hote = _etat.value.connexion.hote
            if (nom.isNotBlank()) preferences.retenirNom(hote, nom)

            // Each snapshot doubles as a measurement; the first one is the baseline.
            mesures?.enregistrer(
                Mesure(
                    horodatage = System.currentTimeMillis(),
                    paquetsActifs = photo.infos.paquetsInstalles,
                    paquetsDesactives = photo.infos.paquetsDesactives,
                    memoireTotaleMo = photo.infos.memoireTotaleMo,
                    memoireLibreMo = photo.infos.memoireLibreMo,
                ),
            )

            // On a phone, the app drawer's apps join the catalogue: see avecApplicationsDuMenu.
            val vu = catalogue.avecApplicationsDuMenu(photo.infos, photo.paquetsSysteme, photo.applicationsMenu)
            _etat.update { courant ->
                courant.copy(
                    chargement = false,
                    catalogue = vu,
                    infos = photo.infos,
                    nomsConnus = if (nom.isBlank()) courant.nomsConnus else courant.nomsConnus + (hote to nom),
                    lignes = vu.entrees.map { entree ->
                        val etatPaquet = photo.etats[entree.paquet] ?: photo.paquetsSysteme[entree.paquet] ?: EtatPaquet.ABSENT
                        LignePaquet(
                            entree = entree,
                            etat = etatPaquet,
                            selectionne = entree.paquet in selection && etatPaquet == EtatPaquet.ACTIF,
                        )
                    },
                    // Packages missing from the catalogue: listed apart, no action offered.
                    inconnus = vu.paquetsInconnus(photo.paquetsSysteme, photo.infos.fabricant),
                )
            }
        }
    }

    // --- Package list ---------------------------------------------------------------------

    fun majRecherche(valeur: String) = _etat.update { it.copy(recherche = valeur) }

    fun majFiltre(filtre: Filtre) = _etat.update { it.copy(filtre = filtre) }

    fun detailler(paquet: String) = _etat.update { it.copy(paquetDetaille = paquet) }

    fun basculerSelection(paquet: String) = _etat.update { it.avecBascule(paquet) }

    fun selectionnerProfil(profil: Profil) = _etat.update { it.avecProfil(profil) }

    fun toutDeselectionner() = _etat.update { it.sansSelection() }

    // --- Confirmation ---------------------------------------------------------------------

    /** Asks for confirmation before disabling; nothing is sent until confirmed. */
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
            is Confirmation.Reinjection -> configuration.reinjecter(demande.plan)
            is Confirmation.Installation -> configuration.installerApk(demande.apk)
            Confirmation.Redemarrage -> redemarrer()
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
            if (InvitationSoutien.merite(resultats)) soutien.remercier()
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

    /** Undoes a single journal action. */
    fun annulerAction(action: ActionJournal) {
        when (action.type) {
            TypeAction.DESACTIVATION -> reactiver(listOf(action.cible))
            TypeAction.PERMISSION, TypeAction.APP_OP -> permissions.annuler(action)
            else -> afficher(texte(Res.string.msg_not_undoable))
        }
    }

    /** Reads the memory breakdown. Kept out of [rafraichir] because the command is heavy. */
    fun rafraichirMemoire() {
        if (!_etat.value.connecte) return
        viewModelScope.launch { lireMemoire(lecteur) }
    }

    /** Reads storage usage, kept separate like memory. */
    fun rafraichirStockage() {
        if (!_etat.value.connecte) return
        viewModelScope.launch { lireStockage(lecteur) }
    }

    /**
     * One memory read at a time, from the tab or the prefetch: `dumpsys meminfo` takes six seconds on the TCL,
     * so a tab opened during the prefetch waits for it instead of starting another.
     */
    private suspend fun lireMemoire(source: LecteurDistant) {
        if (_etat.value.memoireEnLecture) return
        _etat.update { it.copy(memoireEnLecture = true) }
        try {
            val memoire = source.memoire()
            _etat.update { it.copy(memoire = memoire, lectureMemoireTentee = true) }
        } finally {
            _etat.update { it.copy(memoireEnLecture = false) }
        }
    }

    private suspend fun lireStockage(source: LecteurDistant) {
        if (_etat.value.stockageEnLecture) return
        _etat.update { it.copy(stockageEnLecture = true) }
        try {
            val stockage = source.stockage()
            _etat.update { it.copy(stockage = stockage, lectureStockageTentee = true) }
        } finally {
            _etat.update { it.copy(stockageEnLecture = false) }
        }
    }

    /**
     * Reads apps, memory and storage right after connecting, over a second ADB session so the main one stays
     * free. A tab opened later finds its data ready or loading. Only what is missing is read, as on the phone.
     *
     * Apps first: fast once icons are cached, and also needed by the app picker in privileged permissions.
     */
    private fun precharger() {
        val precedent = prechargement
        arreterPrechargement()
        prechargement = viewModelScope.launch {
            // Let the previous run reset its flags first; its session is closed, so it ends quickly.
            precedent?.join()
            val seconde = client.ouvrirSeconde() ?: return@launch
            sessionSeconde = seconde
            try {
                applications.precharger(seconde)
                val lecteurSecond = LecteurDistant(seconde)
                if (!_etat.value.memoire.renseignee) lireMemoire(lecteurSecond)
                if (!_etat.value.stockage.renseignee) lireStockage(lecteurSecond)
            } finally {
                seconde.deconnecter()
                if (sessionSeconde === seconde) sessionSeconde = null
            }
        }
    }

    /** Closing the second session is what interrupts a running read; cancelling the job is not enough. */
    private fun arreterPrechargement() {
        prechargement?.cancel()
        prechargement = null
        sessionSeconde?.deconnecter()
        sessionSeconde = null
    }

    /** Kills an app's processes without changing its install state. */
    fun forcerArret(paquet: String) {
        val moteurActif = moteur ?: return
        viewModelScope.launch {
            val resultat = moteurActif.forcerArret(paquet)
            afficher(
                if (resultat.reussi) {
                    texte(Res.string.msg_stopped, paquet)
                } else {
                    texte(Res.string.msg_failure, resultat.texte())
                },
            )
            rafraichirMemoire()
        }
    }

    /** File name suggested in the save dialog. */
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

    /** Makes the current state the new "before" baseline. */
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
                echecs = echecs.take(MAX_ECHECS).map { it.nom to it.texte() },
            ),
        )
        _etat.update { it.copy(progression = null) }
        toutDeselectionner()
        rafraichir()
    }

    fun demanderRedemarrage() = _etat.update { it.copy(confirmation = Confirmation.Redemarrage) }

    /**
     * Sends the reboot once (`Redemarrage`, in the core), closes the session, then waits for the TV and
     * reconnects silently. The drift card then shows whether the reboot undid anything. Same as on the phone.
     */
    private fun redemarrer() {
        val journalActif = journal ?: return
        val hote = _etat.value.connexion.hote
        val port = _etat.value.connexion.port
        if (redemarrage?.isActive == true) return
        redemarrage = viewModelScope.launch {
            Redemarrage(client, journalActif).redemarrer()
            deconnecter()
            // Not a user "Disconnect": the normal resume may reopen the session later.
            deconnexionVolontaire = false
            _etat.update { it.copy(redemarrage = true) }
            delay(ATTENTE_REDEMARRAGE_MS)
            val revenu = withTimeoutOrNull(DELAI_RETOUR_MS) {
                while (!client.connecter(hote, port, discret = true)) delay(PAS_RETOUR_MS)
                true
            } ?: false
            _etat.update { it.copy(redemarrage = false) }
            if (revenu) {
                ouvrirJournal(hote)
                rafraichir()
                precharger()
                afficher(texte(Res.string.msg_reboot_back))
            } else {
                afficher(texte(Res.string.msg_reboot_not_back))
            }
        }
    }

    private suspend fun ouvrirJournal(hote: String) {
        suiviJournal?.cancel()
        val cle = cleDeFichier(hote)
        val ouvert = JournalRepository(File(emplacements.journaux, "$cle.json"))
        ouvert.charger()
        journal = ouvert
        moteur = MoteurDebloat(client, ouvert)
        installationApk = InstallationApk(client, client, ouvert)

        val relevees = MesuresRepository(File(emplacements.mesures, "$cle.json"))
        relevees.charger()
        mesures = relevees

        _etat.update {
            it.copy(
                memoire = RepartitionMemoire(),
                lectureMemoireTentee = false,
                stockage = RepartitionStockage(),
                lectureStockageTentee = false,
                paquetDetaille = null,
            )
        }
        suiviJournal = viewModelScope.launch {
            launch { ouvert.actions.collect { actions -> _etat.update { it.copy(journal = actions) } } }
            launch { relevees.historique.collect { h -> _etat.update { it.copy(mesures = h) } } }
        }
    }

    private fun afficher(message: MessageUi) = _etat.update { it.copy(message = message) }

    /** The second session must not outlive the window. */
    override fun onCleared() = arreterPrechargement()

    private companion object {
        const val MAX_ECHECS = 4

        /** A TV takes over twenty seconds to reopen ADB; no point knocking sooner. */
        const val ATTENTE_REDEMARRAGE_MS = 20_000L
        const val DELAI_RETOUR_MS = 180_000L
        const val PAS_RETOUR_MS = 5_000L
    }
}
