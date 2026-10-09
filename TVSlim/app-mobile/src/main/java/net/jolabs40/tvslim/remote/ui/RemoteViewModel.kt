package net.jolabs40.tvslim.remote.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.catalog.avecApplicationsDuMenu
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.device.Redemarrage
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
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.AppareilDecouvert
import net.jolabs40.tvslim.remote.adb.ClientAdb
import net.jolabs40.tvslim.remote.adb.DecouverteTv
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.remote.data.PreferencesRemote
import net.jolabs40.tvslim.soutien.InvitationSoutien
import net.jolabs40.tvslim.soutien.PiloteSoutien
import java.io.File
import javax.inject.Inject

/**
 * Main view model: one ADB connection, one catalogue, one log per TV.
 *
 * The debloat engine comes from the shared core and is unaware of where its privileges come from (here, an ADB
 * session opened from the phone).
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
    private var mesures: MesuresRepository? = null
    private var moteur: MoteurDebloat? = null
    private var installationApk: InstallationApk? = null

    val permissions = PilotePermissions(
        contexte = contexte,
        lecteur = lecteur,
        moteur = { moteur },
        portee = viewModelScope,
        afficher = ::afficher,
    )

    /** Support banner, shown after a successful debloat, transfer or install. */
    val soutien = PiloteSoutien(preferences, viewModelScope)

    val configuration = PiloteConfiguration(
        contexte = contexte,
        lecteur = lecteur,
        moteur = { moteur },
        installation = { installationApk },
        console = ConsoleAdb(client) { journal },
        etat = { _etat.value },
        majEtat = { transformation -> _etat.update { transformation(it) } },
        portee = viewModelScope,
        afficher = ::afficher,
        rafraichir = ::rafraichir,
        terminer = { resultats ->
            terminer(resultats.count { it.reussi }, resultats.size, resultats.filterNot { it.reussi })
        },
        remercier = soutien::remercier,
    )

    /** Files tab; follows the connection itself and forgets what it read when the TV changes. */
    val fichiers = PiloteFichiers(contexte, client, viewModelScope, ::afficher, soutien::remercier)

    /** Apps tab; names and icons come from the helper and are cached. Follows the connection itself. */
    val applications = PiloteApplications(
        contexte = contexte,
        client = client,
        portee = viewModelScope,
        afficher = ::afficher,
        etatRemote = { _etat.value },
        moteur = { moteur },
        journal = { journal },
        rafraichirPaquets = ::rafraichir,
        remercier = soutien::remercier,
    )

    /** The TV Slim app on the TV, installed from GitHub. */
    val applicationTv = PiloteApplicationTv(
        contexte = contexte,
        client = client,
        lecteur = lecteur,
        moteur = { moteur },
        installation = { installationApk },
        portee = viewModelScope,
        afficher = ::afficher,
        remercier = soutien::remercier,
    )

    /** TV screenshot, from the top bar. */
    val capture = PiloteCapture(contexte, client, viewModelScope, { _etat.value.infos }, { _etat.value.connecte }, ::afficher)

    /** One log observer at a time, otherwise the previous TV's log would keep writing into the state. */
    private var suiviJournal: Job? = null

    /** One silent reconnect at a time, otherwise each return to the screen would start another. */
    private var reprise: Job? = null

    /** Waits for a rebooted TV to come back. */
    private var redemarrage: Job? = null

    /** Prefetch on connect and the second session carrying it; one at a time. */
    private var prechargement: Job? = null
    private var sessionSeconde: ClientAdb? = null

    /** mDNS discovery, running only while the connection screen is shown. */
    private var veille: Job? = null

    init {
        viewModelScope.launch {
            client.connexion.collect { connexion -> _etat.update { it.copy(connexion = connexion) } }
        }
        viewModelScope.launch {
            permissions.etat.collect { lues -> _etat.update { it.copy(permissions = lues) } }
        }
        viewModelScope.launch {
            // Read first, then update. `update` is a compare-and-set loop that replays its block on concurrent
            // writes, and the two collectors above write at the same time: disk reads inside it could be
            // repeated or applied to a stale snapshot.
            val hote = preferences.dernierHote()
            val port = preferences.dernierPort()
            val noms = preferences.nomsConnus()
            val catalogue = catalogueRepo.catalogue()
            _etat.update {
                it.copy(
                    hoteSaisi = hote,
                    portSaisi = port.toString(),
                    nomsConnus = noms,
                    catalogue = catalogue,
                )
            }
        }
    }

    /** Discovers TVs announcing `_adb._tcp` on the network (network debugging enabled). */
    fun chercherAppareils() {
        if (veille?.isActive == true) return
        veille = viewModelScope.launch {
            decouverte.flux().collect { appareils ->
                _etat.update { courant ->
                    courant.copy(
                        // Cast name first, then the name remembered from a previous connection.
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
            afficher(contexte.getString(R.string.msg_enter_address))
            return
        }
        viewModelScope.launch {
            if (client.connecter(hote, port)) {
                preferences.retenir(hote, port)
                ouvrirJournal(hote)
                rafraichir()
                precharger()
            }
        }
    }

    /**
     * Silently reconnects to the last TV when the app comes back to the foreground.
     *
     * An ADB session does not survive TV standby. The key is authorized and the address known, so there is no
     * reason to show a disconnected app. Failure (usually a TV that is off) shows nothing.
     */
    fun reprendreConnexion() {
        // Connected but nothing read: phone standby cut the previous read. Read again.
        if (_etat.value.connecte) {
            if (_etat.value.infos.marque.isBlank() && _etat.value.infos.modele.isBlank()) {
                rafraichir()
                precharger()
            }
            return
        }
        // During a reboot, the reboot watcher reconnects; avoid racing it.
        if (reprise?.isActive == true || _etat.value.redemarrage) return
        reprise = viewModelScope.launch {
            val hote = _etat.value.hoteSaisi.ifBlank { preferences.dernierHote() }
            if (hote.isBlank()) return@launch
            val port = _etat.value.portSaisi.toIntOrNull() ?: preferences.dernierPort()
            if (client.connecter(hote, port, discret = true)) {
                ouvrirJournal(hote)
                rafraichir()
                precharger()
            }
        }
    }

    /** Applies a scanned pairing code, then connects. */
    fun appliquerScan(valeur: String) {
        val adresse = lireCodeAppairage(valeur)
        if (adresse == null) {
            afficher(contexte.getString(R.string.msg_code_unknown, valeur.trim()))
            return
        }
        _etat.update { it.copy(hoteSaisi = adresse.hote, portSaisi = adresse.port.toString()) }
        connecter()
    }

    fun signalerEchecScan(motif: String) =
        afficher(motif.ifBlank { contexte.getString(R.string.msg_scan_cancelled) })

    fun deconnecter() {
        arreterPrechargement()
        client.deconnecter()
        configuration.oublier()
        reprise?.cancel()
        reprise = null
        suiviJournal?.cancel()
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
                memoire = RepartitionMemoire(),
                stockage = RepartitionStockage(),
            )
        }
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

            // A single command for everything: each network round trip costs.
            val photo = lecteur.photographie(
                paquetsSurveilles = catalogue.entrees.map { it.paquet },
                paquetsDAccueil = paquetsDAccueil,
            )
            // A read cut short (app suspended by Android, session dropped) returns an empty snapshot; keep the
            // previous state instead.
            if (photo.infos.marque.isBlank() && photo.infos.modele.isBlank()) {
                _etat.update { it.copy(chargement = false) }
                return@launch
            }
            val selection = _etat.value.selection.map { it.entree.paquet }.toSet()

            // Remember the model to name the device next time.
            val nom = photo.infos.nomAffiche
            val hote = _etat.value.connexion.hote
            if (nom.isNotBlank()) preferences.retenirNom(hote, nom)

            // Every snapshot is also a measurement; the first one is the baseline the debloated device is
            // compared with.
            mesures?.enregistrer(
                Mesure(
                    horodatage = System.currentTimeMillis(),
                    paquetsActifs = photo.infos.paquetsInstalles,
                    paquetsDesactives = photo.infos.paquetsDesactives,
                    memoireTotaleMo = photo.infos.memoireTotaleMo,
                    memoireLibreMo = photo.infos.memoireLibreMo,
                ),
            )

            // On a phone, launcher apps are added to the catalogue (see avecApplicationsDuMenu).
            val vu = catalogue.avecApplicationsDuMenu(photo.infos, photo.paquetsSysteme, photo.applicationsMenu)
            _etat.update { courant ->
                courant.copy(
                    chargement = false,
                    catalogue = vu,
                    infos = photo.infos,
                    nomsConnus = courant.nomsConnus + (hote to nom),
                    lignes = vu.entrees.map { entree ->
                        val etatPaquet = photo.etats[entree.paquet] ?: photo.paquetsSysteme[entree.paquet] ?: EtatPaquet.ABSENT
                        LignePaquet(
                            entree = entree,
                            etat = etatPaquet,
                            selectionne = entree.paquet in selection &&
                                etatPaquet == EtatPaquet.ACTIF,
                        )
                    },
                    // Packages missing from the catalogue: listed separately, no action offered.
                    inconnus = vu.paquetsInconnus(photo.paquetsSysteme, photo.infos.fabricant),
                )
            }
        }
    }

    fun basculerSelection(paquet: String) = _etat.update { it.avecBascule(paquet) }

    fun selectionnerProfil(profil: Profil) = _etat.update { it.avecProfil(profil) }

    fun toutDeselectionner() = _etat.update { it.sansSelection() }

    // --- Confirmation ---------------------------------------------------------------------

    /** Asks for confirmation before disabling; nothing is sent until confirmed. */
    fun demanderApplication() {
        val courant = _etat.value
        val choisies = courant.selection.map { it.entree }
        when {
            choisies.isEmpty() -> afficher(contexte.getString(R.string.msg_nothing_selected))
            !courant.connecte -> afficher(contexte.getString(R.string.msg_connect_first))
            else -> _etat.update { it.copy(confirmation = Confirmation.Application(choisies)) }
        }
    }

    fun demanderRestauration() {
        val aRestaurer = journal?.paquetsADesactivationActive().orEmpty()
        when {
            aRestaurer.isEmpty() -> afficher(contexte.getString(R.string.msg_nothing_to_restore))
            !_etat.value.connecte -> afficher(contexte.getString(R.string.msg_connect_first))
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
            terminer(resultats.count { it.reussi }, resultats.size, resultats.filterNot { it.reussi })
            if (InvitationSoutien.merite(resultats)) soutien.remercier()
        }
    }

    fun reactiver(paquets: List<String>) {
        val moteurActif = moteur
        if (paquets.isEmpty() || moteurActif == null) {
            afficher(contexte.getString(R.string.msg_nothing_to_restore))
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

    /** Undoes a single log entry. */
    fun annulerAction(action: ActionJournal) {
        when (action.type) {
            TypeAction.DESACTIVATION -> reactiver(listOf(action.cible))
            TypeAction.PERMISSION, TypeAction.APP_OP -> permissions.annuler(action)
            else -> afficher(contexte.getString(R.string.msg_not_undoable))
        }
    }

    /** Reads the memory breakdown. Kept out of [rafraichir] because the command is heavy. */
    fun rafraichirMemoire() {
        if (!_etat.value.connecte) return
        viewModelScope.launch { lireMemoire(lecteur) }
    }

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
            _etat.update { it.copy(memoire = memoire) }
        } finally {
            _etat.update { it.copy(memoireEnLecture = false) }
        }
    }

    private suspend fun lireStockage(source: LecteurDistant) {
        if (_etat.value.stockageEnLecture) return
        _etat.update { it.copy(stockageEnLecture = true) }
        try {
            val stockage = source.stockage()
            _etat.update { it.copy(stockage = stockage) }
        } finally {
            _etat.update { it.copy(stockageEnLecture = false) }
        }
    }

    /**
     * Prefetches apps, memory and storage on connect through a second ADB session, leaving the main one free for
     * user actions; tabs then find their data read or being read. Only missing data is read; failures are silent
     * and the tab reads again on demand.
     *
     * Apps come first: fast once icons are cached, and also needed by the permissions card's app picker.
     */
    private fun precharger() {
        val precedent = prechargement
        arreterPrechargement()
        prechargement = viewModelScope.launch {
            // Let the previous prefetch reset its flags first; its session is closed, so it ends quickly.
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

    /** Closing the second session is what interrupts the read in progress; cancelling the job is not enough. */
    private fun arreterPrechargement() {
        prechargement?.cancel()
        prechargement = null
        sessionSeconde?.deconnecter()
        sessionSeconde = null
    }

    /** Stops an app's processes without changing its enabled state. */
    fun forcerArret(paquet: String) {
        val moteurActif = moteur ?: return
        viewModelScope.launch {
            val resultat = moteurActif.forcerArret(paquet)
            afficher(
                if (resultat.reussi) {
                    contexte.getString(R.string.msg_stopped, paquet)
                } else {
                    contexte.getString(R.string.msg_failure, resultat.texte(contexte))
                },
            )
            rafraichirMemoire()
        }
    }

    fun exporterJournal() {
        val actif = journal ?: return
        viewModelScope.launch {
            val infos = _etat.value.infos
            val dossier = contexte.getExternalFilesDir(null) ?: contexte.filesDir
            val nom = "TVSlim-${cleDeFichier(_etat.value.connexion.hote)}.md"
            val chemin = actif.exporterMarkdown(
                cible = File(dossier, nom),
                entete = "Appareil : ${infos.marque} ${infos.modele} — Android " +
                    "${infos.versionAndroid} (${infos.build})",
            )
            afficher(contexte.getString(R.string.msg_journal_exported, chemin))
        }
    }

    fun effacerMessage() = _etat.update { it.copy(message = null) }

    private fun terminer(
        succes: Int,
        total: Int,
        echecs: List<ResultatAction>,
    ) {
        afficher(
            buildString {
                append(contexte.getString(R.string.result_summary, succes, total))
                echecs.take(MAX_ECHECS).forEach { append("\n${it.nom} : ${it.texte(contexte)}") }
            },
        )
        _etat.update { it.copy(progression = null) }
        toutDeselectionner()
        rafraichir()
    }

    /** Makes the current state the new baseline, e.g. after a factory reset or before a new pass. */
    fun redefinirReference() {
        val actives = mesures ?: return
        viewModelScope.launch {
            actives.redefinirReference()
            afficher(contexte.getString(R.string.msg_baseline_reset))
        }
    }

    fun demanderRedemarrage() = _etat.update { it.copy(confirmation = Confirmation.Redemarrage) }

    /**
     * Sends the reboot once (the core's `Redemarrage`), closes the session, then waits for the TV to come back and
     * reconnects silently. The drift card then shows whether the reboot undid anything.
     */
    private fun redemarrer() {
        val journalActif = journal ?: return
        val hote = _etat.value.connexion.hote
        val port = _etat.value.connexion.port
        if (redemarrage?.isActive == true) return
        redemarrage = viewModelScope.launch {
            Redemarrage(client, journalActif).redemarrer()
            deconnecter()
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
                afficher(contexte.getString(R.string.msg_reboot_back))
            } else {
                afficher(contexte.getString(R.string.msg_reboot_not_back))
            }
        }
    }

    private suspend fun ouvrirJournal(hote: String) {
        suiviJournal?.cancel()
        val cle = cleDeFichier(hote)
        val ouvert = JournalRepository(File(File(contexte.filesDir, "journaux"), "$cle.json"))
        ouvert.charger()
        journal = ouvert
        moteur = MoteurDebloat(client, ouvert)
        installationApk = InstallationApk(client, client, ouvert)

        val relevees = MesuresRepository(File(File(contexte.filesDir, "mesures"), "$cle.json"))
        relevees.charger()
        mesures = relevees

        // Memory and storage read earlier belong to another session, possibly another TV.
        _etat.update { it.copy(memoire = RepartitionMemoire(), stockage = RepartitionStockage()) }
        suiviJournal = viewModelScope.launch {
            launch {
                ouvert.actions.collect { actions -> _etat.update { it.copy(journal = actions) } }
            }
            launch {
                relevees.historique.collect { h -> _etat.update { it.copy(mesures = h) } }
            }
        }
    }

    private fun afficher(texte: String) = _etat.update { it.copy(message = texte) }

    /** Closes the second session; the main one is a shared singleton and stays open. */
    override fun onCleared() = arreterPrechargement()

    private companion object {
        const val MAX_ECHECS = 4

        /** A TV takes over twenty seconds to reopen ADB; no point trying earlier. */
        const val ATTENTE_REDEMARRAGE_MS = 20_000L
        const val DELAI_RETOUR_MS = 180_000L
        const val PAS_RETOUR_MS = 5_000L
    }
}
