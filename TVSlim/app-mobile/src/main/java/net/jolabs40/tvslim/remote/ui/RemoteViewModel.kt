package net.jolabs40.tvslim.remote.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.Profil
import net.jolabs40.tvslim.catalog.avecApplicationsDuMenu
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LecteurDistant
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
    private var mesures: MesuresRepository? = null
    private var moteur: MoteurDebloat? = null
    private var installationApk: InstallationApk? = null

    /**
     * Les permissions privilégiées ont leur propre pilote : leur état — un paquet, une
     * permission, ce que le téléviseur en dit — n'a rien à voir avec celui du débloat.
     */
    val permissions = PilotePermissions(
        contexte = contexte,
        lecteur = lecteur,
        moteur = { moteur },
        portee = viewModelScope,
        afficher = ::afficher,
    )

    /** Le bandeau de soutien, après un débloat, un transfert ou une installation qui ont abouti. */
    val soutien = PiloteSoutien(preferences, viewModelScope)

    /**
     * L'écran d'accueil — fiche du launcher, guet de son installation — et la configuration qu'on
     * sauvegarde puis réinjecte ont aussi le leur : ils ne partagent que l'état et le moteur.
     */
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

    /** L'onglet Fichiers : il suit lui-même la connexion, et oublie ce qu'il a lu quand le téléviseur change. */
    val fichiers = PiloteFichiers(contexte, client, viewModelScope, ::afficher, soutien::remercier)

    /** L'onglet Applications : noms et icônes lus par l'aide, gardés en cache ; il suit lui-même la connexion. */
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

    /** La capture d'écran du téléviseur, depuis la barre du haut. */
    val capture = PiloteCapture(contexte, client, viewModelScope, { _etat.value.infos }, { _etat.value.connecte }, ::afficher)

    /** Une seule observation de journal à la fois : sinon celui de la TV précédente écrirait encore. */
    private var suiviJournal: Job? = null

    /** Une reconnexion silencieuse à la fois, sinon le retour à l'écran en lancerait une chaque fois. */
    private var reprise: Job? = null

    /** La découverte mDNS ne tourne que pendant qu'on regarde l'écran de connexion. */
    private var veille: Job? = null

    init {
        viewModelScope.launch {
            client.connexion.collect { connexion -> _etat.update { it.copy(connexion = connexion) } }
        }
        viewModelScope.launch {
            permissions.etat.collect { lues -> _etat.update { it.copy(permissions = lues) } }
        }
        viewModelScope.launch {
            // Les lectures d'abord, la mise à jour ensuite. `update` est une boucle de
            // comparaison-et-échange : elle rejoue son bloc quand quelqu'un d'autre a écrit
            // entre-temps — et il y a quelqu'un, les deux `collect` ci-dessus alimentant le
            // même état au même instant. Quatre lectures disque rejouées, au mieux du travail
            // refait, au pire un état reconstruit sur une photographie périmée.
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
            afficher(contexte.getString(R.string.msg_enter_address))
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
     * Retente le dernier téléviseur au retour dans l'application, sans le demander.
     *
     * Une session ADB ne survit pas à la mise en veille du téléviseur ; retrouver l'application
     * déconnectée après avoir simplement changé de fenêtre n'a aucun sens, alors que la clé est
     * autorisée et que l'adresse est connue. En cas d'échec — téléviseur éteint, le cas le plus
     * banal — rien ne s'affiche : la personne n'a rien demandé.
     */
    fun reprendreConnexion() {
        if (_etat.value.connecte || reprise?.isActive == true) return
        reprise = viewModelScope.launch {
            val hote = _etat.value.hoteSaisi.ifBlank { preferences.dernierHote() }
            if (hote.isBlank()) return@launch
            val port = _etat.value.portSaisi.toIntOrNull() ?: preferences.dernierPort()
            if (client.connecter(hote, port, discret = true)) {
                ouvrirJournal(hote)
                rafraichir()
            }
        }
    }

    /** Applique le contenu d'un code scanné, puis se connecte dans la foulée. */
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
        _etat.update {
            it.copy(
                lignes = emptyList(),
                inconnus = emptyList(),
                infos = InfosAppareil.VIDE,
                journal = emptyList(),
                mesures = HistoriqueMesures(),
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

            // Chaque photographie sert aussi de mesure : la première fait référence, et c'est
            // à elle qu'on comparera l'appareil une fois dégraissé.
            mesures?.enregistrer(
                Mesure(
                    horodatage = System.currentTimeMillis(),
                    paquetsActifs = photo.infos.paquetsInstalles,
                    paquetsDesactives = photo.infos.paquetsDesactives,
                    memoireTotaleMo = photo.infos.memoireTotaleMo,
                    memoireLibreMo = photo.infos.memoireLibreMo,
                ),
            )

            // Un téléphone ajoute ses applications du menu au catalogue : voir avecApplicationsDuMenu.
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
                    // Ce que le catalogue ne décrit pas : montré à part, sans rien proposer.
                    inconnus = vu.paquetsInconnus(photo.paquetsSysteme, photo.infos.fabricant),
                )
            }
        }
    }

    fun basculerSelection(paquet: String) = _etat.update { it.avecBascule(paquet) }

    fun selectionnerProfil(profil: Profil) = _etat.update { it.avecProfil(profil) }

    fun toutDeselectionner() = _etat.update { it.sansSelection() }

    // --- Confirmation ---------------------------------------------------------------------

    /** Demande confirmation avant de désactiver : rien ne part tant que ce n'est pas validé. */
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

    /** Annule une action précise du journal, sans toucher au reste. */
    fun annulerAction(action: ActionJournal) {
        when (action.type) {
            TypeAction.DESACTIVATION -> reactiver(listOf(action.cible))
            TypeAction.PERMISSION, TypeAction.APP_OP -> permissions.annuler(action)
            else -> afficher(contexte.getString(R.string.msg_not_undoable))
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

    /** Lit l'occupation du stockage, à la demande comme la mémoire : seul son onglet en a besoin. */
    fun rafraichirStockage() {
        if (!_etat.value.connecte) return
        viewModelScope.launch {
            _etat.update { it.copy(chargement = true) }
            val stockage = lecteur.stockage()
            _etat.update { it.copy(chargement = false, stockage = stockage) }
        }
    }

    /** Arrête les processus d'une application, sans rien changer à son état d'installation. */
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

    /**
     * Repart d'une page blanche : l'état actuel devient le « avant ». Utile après une remise à
     * zéro du téléviseur, ou quand on veut mesurer une nouvelle passe.
     */
    fun redefinirReference() {
        val actives = mesures ?: return
        viewModelScope.launch {
            actives.redefinirReference()
            afficher(contexte.getString(R.string.msg_baseline_reset))
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

    private companion object {
        const val MAX_ECHECS = 4
    }
}
