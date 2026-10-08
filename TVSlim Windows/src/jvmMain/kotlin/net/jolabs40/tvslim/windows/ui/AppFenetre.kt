package net.jolabs40.tvslim.windows.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jolabs40.tvslim.fichiers.EntreeDistante
import net.jolabs40.tvslim.soutien.InvitationSoutien
import net.jolabs40.tvslim.windows.InfosApp
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.maj.PiloteMisesAJour
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.about_contact_address
import net.jolabs40.tvslim.windows.ressources.about_title
import net.jolabs40.tvslim.windows.ressources.about_website_url
import net.jolabs40.tvslim.windows.ressources.app_name
import net.jolabs40.tvslim.windows.ressources.baseline_apps_24
import net.jolabs40.tvslim.windows.ressources.baseline_cast_24
import net.jolabs40.tvslim.windows.ressources.baseline_folder_24
import net.jolabs40.tvslim.windows.ressources.baseline_history_24
import net.jolabs40.tvslim.windows.ressources.baseline_info_24
import net.jolabs40.tvslim.windows.ressources.baseline_inventory_2_24
import net.jolabs40.tvslim.windows.ressources.baseline_memory_24
import net.jolabs40.tvslim.windows.ressources.config_open_dialog
import net.jolabs40.tvslim.windows.ressources.config_save_dialog
import net.jolabs40.tvslim.windows.ressources.files_pick_destination_file
import net.jolabs40.tvslim.windows.ressources.files_pick_destination_folder
import net.jolabs40.tvslim.windows.ressources.files_pick_files
import net.jolabs40.tvslim.windows.ressources.files_pick_folder
import net.jolabs40.tvslim.windows.ressources.install_dialog
import net.jolabs40.tvslim.windows.ressources.journal_export_dialog
import net.jolabs40.tvslim.windows.ressources.scrcpy_window_mirror
import net.jolabs40.tvslim.windows.ressources.status_connected
import net.jolabs40.tvslim.windows.ressources.status_connecting
import net.jolabs40.tvslim.windows.ressources.status_disconnected
import net.jolabs40.tvslim.windows.ressources.status_error
import net.jolabs40.tvslim.windows.ressources.tab_apps
import net.jolabs40.tvslim.windows.ressources.tab_connection
import net.jolabs40.tvslim.windows.ressources.tab_files
import net.jolabs40.tvslim.windows.ressources.tab_log
import net.jolabs40.tvslim.windows.ressources.tab_memory
import net.jolabs40.tvslim.windows.ressources.tab_packages
import net.jolabs40.tvslim.windows.ressources.unknown_export_dialog
import net.jolabs40.tvslim.windows.ui.ecrans.AProposDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ActionsApplicationsUi
import net.jolabs40.tvslim.windows.ui.ecrans.ActionsEcran
import net.jolabs40.tvslim.windows.ui.ecrans.ActionsFichiers
import net.jolabs40.tvslim.windows.ui.ecrans.ApercuCaptureDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ApplicationsEcran
import net.jolabs40.tvslim.windows.ui.ecrans.BanniereMiseAJour
import net.jolabs40.tvslim.windows.ui.ecrans.BanniereSoutien
import net.jolabs40.tvslim.windows.ui.ecrans.BoutonSoutien
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ConnexionEcran
import net.jolabs40.tvslim.windows.ui.ecrans.FermetureDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.FichiersEcran
import net.jolabs40.tvslim.windows.ui.ecrans.JournalEcran
import net.jolabs40.tvslim.windows.ui.ecrans.MemoireEcran
import net.jolabs40.tvslim.windows.ui.ecrans.PaquetsEcran
import net.jolabs40.tvslim.windows.ui.ecrans.TelechargementScrcpyDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.VideoEnregistreeDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.VoileDepot
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.net.URI

/** Les six onglets du compagnon, dans le même ordre et avec les mêmes icônes. */
enum class Onglet(val titre: StringResource, val icone: DrawableResource) {
    TELEVISEUR(Res.string.tab_connection, Res.drawable.baseline_cast_24),
    PAQUETS(Res.string.tab_packages, Res.drawable.baseline_inventory_2_24),
    APPLICATIONS(Res.string.tab_apps, Res.drawable.baseline_apps_24),
    MEMOIRE(Res.string.tab_memory, Res.drawable.baseline_memory_24),
    FICHIERS(Res.string.tab_files, Res.drawable.baseline_folder_24),
    JOURNAL(Res.string.tab_log, Res.drawable.baseline_history_24),
}

/**
 * La fenêtre : un rail d'onglets à gauche — la barre du bas d'un téléphone, couchée sur le côté —
 * le bandeau de mise à jour en haut, et chaque retour d'action dans la même bannière en bas.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun AppFenetre(
    pilote: PiloteApp,
    misesAJour: PiloteMisesAJour,
    /** Capture, miroir et vidéo de l'écran du téléviseur. */
    ecran: PiloteEcran,
    /** Où scrcpy arrive quand il faut le télécharger : dit dans la fenêtre qui le propose. */
    dossierScrcpy: File,
    onglet: Onglet,
    onOnglet: (Onglet) -> Unit,
    ouvrirLien: (String) -> Unit,
    ouvrirDossierDonnees: () -> Unit,
    choisirFichierExport: (nomPropose: String, titre: String) -> File?,
    choisirFichierImport: (titre: String) -> File?,
    choisirApk: (titre: String) -> File?,
    choisirFichiers: (titre: String) -> List<File>,
    choisirDossier: (titre: String) -> File?,
    /** « Enregistrer sous », ouvert sur Téléchargements, pour un fichier copié du téléviseur. */
    choisirDestinationFichier: (nomPropose: String, titre: String) -> File?,
    /** Le dossier qui recevra un dossier copié du téléviseur, choisi depuis Téléchargements. */
    choisirDestinationDossier: (titre: String) -> File?,
    ouvrirDossier: (File) -> Unit,
) {
    val etat by pilote.etat.collectAsStateWithLifecycle()
    val etatFichiers by pilote.fichiers.explorateur.etat.collectAsStateWithLifecycle()
    val etatApplications by pilote.applications.etat.collectAsStateWithLifecycle()
    val etatApplicationTv by pilote.applicationTv.etat.collectAsStateWithLifecycle()
    val etatMaj by misesAJour.etat.collectAsStateWithLifecycle()
    val soutienVisible by pilote.soutien.visible.collectAsStateWithLifecycle()
    val etatEcran by ecran.etat.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    var aPropos by remember { mutableStateOf(false) }
    val titreExport = stringResource(Res.string.journal_export_dialog)
    val titreSauvegarde = stringResource(Res.string.config_save_dialog)
    val titreReinjection = stringResource(Res.string.config_open_dialog)
    val titreInconnus = stringResource(Res.string.unknown_export_dialog)
    val titreApk = stringResource(Res.string.install_dialog)
    val titreFichiers = stringResource(Res.string.files_pick_files)
    val titreDossier = stringResource(Res.string.files_pick_folder)
    val titreDestinationFichier = stringResource(Res.string.files_pick_destination_file)
    val titreDestinationDossier = stringResource(Res.string.files_pick_destination_folder)
    // Le titre de la fenêtre de scrcpy : c'est par lui qu'on la reconnaît dans la barre des tâches.
    val nomTv = etat.infos.nomAffiche.ifBlank { etat.connexion.hote }
    val titreMiroir = stringResource(Res.string.scrcpy_window_mirror, nomTv)

    // Un APK glissé depuis l'Explorateur, n'importe où dans la fenêtre : la carte d'installation n'est pas
    // forcément à l'écran quand on a le fichier sous la main. Le voile dit où il va partir. Sur l'onglet
    // Fichiers, tout ce qu'on glisse — fichiers et dossiers, APK compris — part dans le dossier affiché.
    var survol by remember { mutableStateOf(false) }
    val ongletCourant by rememberUpdatedState(onglet)
    val depot = remember(pilote) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                survol = true
            }

            override fun onExited(event: DragAndDropEvent) {
                survol = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                survol = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                survol = false
                val liens = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                val fichiers = liens.mapNotNull { lien -> runCatching { File(URI(lien)) }.getOrNull() }
                if (fichiers.isEmpty()) return false
                if (ongletCourant == Onglet.FICHIERS) {
                    if (!pilote.etat.value.connecte) return false
                    pilote.fichiers.deposer(fichiers)
                } else {
                    pilote.configuration.choisirApk(fichiers.first())
                }
                return true
            }
        }
    }

    // Une session ADB ne survit pas à la veille du téléviseur. Au retour sur la fenêtre — sortie de
    // la barre des tâches — on retente le dernier téléviseur sans rien demander.
    val proprietaire = LocalLifecycleOwner.current
    DisposableEffect(proprietaire) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_START) pilote.reprendreConnexion()
        }
        proprietaire.lifecycle.addObserver(observateur)
        onDispose { proprietaire.lifecycle.removeObserver(observateur) }
    }

    // Chaque retour d'action passe par la même bannière, puis est consommé.
    LaunchedEffect(etat.message) {
        val message = etat.message ?: return@LaunchedEffect
        val texte = message.rediger()
        messages.showSnackbar(
            message = texte,
            withDismissAction = true,
            duration = if ('\n' in texte) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        pilote.effacerMessage()
    }
    LaunchedEffect(etatEcran.message) {
        val message = etatEcran.message ?: return@LaunchedEffect
        messages.showSnackbar(message = message.rediger(), withDismissAction = true)
        ecran.effacerMessage()
    }

    etatEcran.capture?.let { capture ->
        ApercuCaptureDialogue(
            capture = capture,
            onCopier = ecran::copierCapture,
            onOuvrirDossier = { capture.fichier.parentFile?.let(ouvrirDossier) },
            onFermer = ecran::fermerCapture,
        )
    }
    if (etatEcran.telechargementPropose != null) {
        TelechargementScrcpyDialogue(
            phase = etatEcran.scrcpy,
            dossier = dossierScrcpy,
            onTelecharger = ecran::accepterTelechargement,
            onAnnuler = ecran::refuserTelechargement,
        )
    }
    if (etatEcran.fermeture) FermetureDialogue(etatEcran.enregistrement)
    etatEcran.video?.let { video ->
        VideoEnregistreeDialogue(
            video = video,
            onOuvrirDossier = { video.parentFile?.let(ouvrirDossier) },
            onFermer = ecran::fermerVideo,
        )
    }

    etat.confirmation?.let { demande ->
        ConfirmationDialogue(
            confirmation = demande,
            onConfirmer = pilote::confirmer,
            onAnnuler = pilote::annulerConfirmation,
        )
    }

    val siteWeb = stringResource(Res.string.about_website_url)
    val contact = stringResource(Res.string.about_contact_address)
    if (aPropos) {
        AProposDialogue(
            etat = etatMaj,
            onFermer = { aPropos = false },
            onVerifier = { misesAJour.verifier() },
            onVerificationAuto = misesAJour::majVerificationAuto,
            onInstaller = misesAJour::installer,
            // Dans la langue de l'application : la page anglaise est à la racine, la française sous /fr/.
            onSite = { ouvrirLien(siteWeb) },
            onContact = { ouvrirLien("mailto:$contact") },
            onSource = { ouvrirLien("https://github.com/${InfosApp.DEPOT_GITHUB}") },
            onSoutenir = { ouvrirLien(InvitationSoutien.LIEN) },
            onDossier = ouvrirDossierDonnees,
        )
    }

    val actionsApplicationTv = remember(pilote) {
        ActionsApplicationTv(
            onLire = pilote.applicationTv::lire,
            onInstaller = pilote.applicationTv::installer,
            onAutoriser = pilote.applicationTv::autoriser,
        )
    }
    val actionsPermissions = remember(pilote) {
        ActionsPermissions(
            onPaquet = pilote.permissions::majPaquet,
            onPermission = pilote.permissions::majPermission,
            onLire = pilote.permissions::lire,
            onAccorder = pilote.permissions::accorder,
            onRetirer = pilote.permissions::retirer,
            onChoisirPaquet = pilote.permissions::choisirPaquet,
            onChargerApplications = pilote.applications::charger,
        )
    }
    val actionsCommande = remember(pilote) {
        ActionsCommande(
            onSaisie = pilote.configuration::saisirCommande,
            onEnvoyer = pilote.configuration::envoyerCommande,
            onRappel = pilote.configuration::rappelerCommande,
        )
    }

    Scaffold(
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
            target = depot,
        ),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                actions = {
                    ActionsEcran(
                        etat = etatEcran,
                        connecte = etat.connecte,
                        onCapturer = ecran::capturer,
                        onMiroir = { ecran.ouvrirMiroir(titreMiroir) },
                        onArreterMiroir = ecran::arreterMiroir,
                        onEnregistrer = ecran::enregistrer,
                        onArreterEnregistrement = ecran::arreterEnregistrement,
                    )
                    PastilleConnexion(etat)
                    BoutonSoutien(onClick = { ouvrirLien(InvitationSoutien.LIEN) })
                    IconButton(onClick = { aPropos = true }) {
                        Icon(
                            painter = painterResource(Res.drawable.baseline_info_24),
                            contentDescription = stringResource(Res.string.about_title),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { marges ->
        Row(modifier = Modifier.fillMaxSize().padding(marges)) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(8.dp))
                Onglet.entries.forEach { cible ->
                    NavigationRailItem(
                        selected = cible == onglet,
                        onClick = { onOnglet(cible) },
                        icon = { Icon(painterResource(cible.icone), contentDescription = null) },
                        label = { Text(stringResource(cible.titre)) },
                    )
                }
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                BanniereMiseAJour(
                    etat = etatMaj,
                    onInstaller = misesAJour::installer,
                    onPage = misesAJour::ouvrirPage,
                    onPlusTard = misesAJour::ecarter,
                )
                BanniereSoutien(
                    visible = soutienVisible,
                    onSoutenir = {
                        ouvrirLien(InvitationSoutien.LIEN)
                        pilote.soutien.ecarter()
                    },
                    onDejaFait = pilote.soutien::declarerDon,
                    onPlusTard = pilote.soutien::ecarter,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (onglet) {
                        Onglet.TELEVISEUR -> ConnexionEcran(
                            etat = etat,
                            onHote = pilote::majHote,
                            onPort = pilote::majPort,
                            onConnecter = pilote::connecter,
                            onDeconnecter = pilote::deconnecter,
                            onActualiser = pilote::rafraichir,
                            onInstallerLauncher = pilote.configuration::installerLauncher,
                            onDefinirAccueil = pilote.configuration::definirAccueil,
                            onOuvrirLien = ouvrirLien,
                            onReprendreDerive = pilote.configuration::proposerDerive,
                            onChercher = pilote::chercherAppareils,
                            onArreterRecherche = pilote::arreterRecherche,
                            onConnecterA = pilote::connecterA,
                            actionsPermissions = actionsPermissions,
                            etatApplications = etatApplications,
                            etatApplicationTv = etatApplicationTv,
                            actionsApplicationTv = actionsApplicationTv,
                            onChoisirApk = { choisirApk(titreApk)?.let(pilote.configuration::choisirApk) },
                            actionsCommande = actionsCommande,
                            onRedemarrer = pilote::demanderRedemarrage,
                        )

                        Onglet.PAQUETS -> PaquetsEcran(
                            etat = etat,
                            onBasculer = pilote::basculerSelection,
                            onDetailler = pilote::detailler,
                            onProfil = pilote::selectionnerProfil,
                            onToutDecocher = pilote::toutDeselectionner,
                            onAppliquer = pilote::demanderApplication,
                            onReactiver = { pilote.reactiver(listOf(it)) },
                            onRecherche = pilote::majRecherche,
                            onFiltre = pilote::majFiltre,
                            onSauvegarder = {
                                choisirFichierExport(pilote.configuration.nomFichier(), titreSauvegarde)
                                    ?.let(pilote.configuration::sauvegarder)
                            },
                            onReinjecter = {
                                choisirFichierImport(titreReinjection)?.let(pilote.configuration::charger)
                            },
                            onExporterInconnus = {
                                choisirFichierExport(pilote.configuration.nomExportInconnus(), titreInconnus)
                                    ?.let { pilote.configuration.exporterInconnus(it) }
                            },
                            // Le même export, puis le formulaire du catalogue dans le navigateur.
                            onProposerInconnus = {
                                choisirFichierExport(pilote.configuration.nomExportInconnus(), titreInconnus)
                                    ?.let { pilote.configuration.exporterInconnus(it, puisOuvrir = ouvrirLien) }
                            },
                        )

                        Onglet.APPLICATIONS -> ApplicationsEcran(
                            connecte = etat.connecte,
                            etat = etatApplications,
                            actions = remember(pilote) {
                                val applications = pilote.applications
                                ActionsApplicationsUi(
                                    onCharger = applications::charger,
                                    onRecherche = applications::majRecherche,
                                    onOuvrir = applications::ouvrir,
                                    onArreter = applications::forcerArret,
                                    onDesactiver = applications::demanderDesactivation,
                                    onReactiver = applications::reactiver,
                                    onDesinstaller = applications::demanderDesinstallation,
                                    onConfirmer = applications::confirmer,
                                    onAnnuler = applications::annulerConfirmation,
                                    desactivable = applications::entreeDesactivable,
                                )
                            },
                        )

                        Onglet.MEMOIRE -> MemoireEcran(
                            etat = etat,
                            onActualiser = pilote::rafraichirMemoire,
                            onActualiserStockage = pilote::rafraichirStockage,
                            onForcerArret = pilote::forcerArret,
                            onRedefinirReference = pilote::redefinirReference,
                        )

                        Onglet.FICHIERS -> FichiersEcran(
                            connecte = etat.connecte,
                            etat = etatFichiers,
                            actions = actionsFichiers(
                                pilote = pilote.fichiers,
                                onEnvoyerFichiers = {
                                    choisirFichiers(titreFichiers).takeIf { it.isNotEmpty() }
                                        ?.let(pilote.fichiers::deposer)
                                },
                                onEnvoyerDossier = {
                                    choisirDossier(titreDossier)?.let { pilote.fichiers.deposer(listOf(it)) }
                                },
                                onCopier = { entree ->
                                    val choix = if (entree.dossier) {
                                        choisirDestinationDossier(titreDestinationDossier)
                                    } else {
                                        choisirDestinationFichier(entree.nom, titreDestinationFichier)
                                    }
                                    choix?.let { pilote.fichiers.copier(entree, it) }
                                },
                                onOuvrirDossierLocal = { chemin -> ouvrirDossier(File(chemin)) },
                            ),
                        )

                        Onglet.JOURNAL -> JournalEcran(
                            etat = etat,
                            onToutRestaurer = pilote::demanderRestauration,
                            onExporter = {
                                choisirFichierExport(pilote.nomExportJournal(), titreExport)
                                    ?.let(pilote::exporterJournal)
                            },
                            onAnnulerAction = pilote::annulerAction,
                        )
                    }
                    if (survol) {
                        VoileDepot(
                            connecte = etat.connecte,
                            nomTeleviseur = etat.infos.nomAffiche.ifBlank { etat.connexion.hote },
                            destination = etatFichiers.chemin.takeIf { onglet == Onglet.FICHIERS },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun actionsFichiers(
    pilote: PiloteFichiers,
    onEnvoyerFichiers: () -> Unit,
    onEnvoyerDossier: () -> Unit,
    onCopier: (EntreeDistante) -> Unit,
    onOuvrirDossierLocal: (String) -> Unit,
): ActionsFichiers {
    val explorateur = pilote.explorateur
    return remember(pilote) {
        ActionsFichiers(
            onDemarrer = explorateur::demarrer,
            onOuvrir = explorateur::ouvrir,
            onRemonter = explorateur::remonter,
            onActualiser = explorateur::actualiser,
            onEnvoyerFichiers = onEnvoyerFichiers,
            onEnvoyerDossier = onEnvoyerDossier,
            onCreerDossier = explorateur::creerDossier,
            onConfirmer = explorateur::confirmer,
            onAnnulerConfirmation = explorateur::annulerConfirmation,
            onArreter = explorateur::annulerEnvoi,
            onCopier = onCopier,
            onConfirmerCopie = explorateur::confirmerRapatriement,
            onAnnulerCopie = explorateur::annulerRapatriement,
            onSupprimer = explorateur::demanderSuppression,
            onConfirmerSuppression = explorateur::confirmerSuppression,
            onAnnulerSuppression = explorateur::annulerSuppression,
            onOuvrirDossierLocal = onOuvrirDossierLocal,
        )
    }
}

/** Où en est la connexion, lisible de n'importe quel onglet. */
@Composable
private fun PastilleConnexion(etat: EtatApp) {
    val connexion = etat.connexion
    val nom = etat.infos.nomAffiche.ifBlank { etat.nomsConnus[connexion.hote] ?: connexion.hote }
    val (texte, couleur) = when (connexion.etat) {
        EtatConnexion.CONNECTE ->
            stringResource(Res.string.status_connected, nom) to MaterialTheme.colorScheme.primary

        EtatConnexion.CONNEXION ->
            stringResource(Res.string.status_connecting, connexion.hote) to MaterialTheme.colorScheme.tertiary

        EtatConnexion.ERREUR ->
            stringResource(Res.string.status_error, connexion.hote) to MaterialTheme.colorScheme.error

        EtatConnexion.DECONNECTE ->
            stringResource(Res.string.status_disconnected) to MaterialTheme.colorScheme.outline
    }
    Row(modifier = Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(couleur, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text = texte, style = MaterialTheme.typography.bodyMedium)
    }
}
