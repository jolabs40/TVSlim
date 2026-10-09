package net.jolabs40.tvslim.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.screens.ActionsApplicationsUi
import net.jolabs40.tvslim.remote.ui.screens.ActionsFichiers
import net.jolabs40.tvslim.remote.ui.screens.AProposDialogue
import net.jolabs40.tvslim.remote.ui.screens.ApercuCaptureDialogue
import net.jolabs40.tvslim.remote.ui.screens.ApplicationsScreen
import net.jolabs40.tvslim.remote.ui.screens.BanniereSoutien
import net.jolabs40.tvslim.remote.ui.screens.BoutonAPropos
import net.jolabs40.tvslim.remote.ui.screens.BoutonCapture
import net.jolabs40.tvslim.remote.ui.screens.BoutonSoutien
import net.jolabs40.tvslim.remote.ui.screens.ConfirmationDialogue
import net.jolabs40.tvslim.remote.ui.screens.ConnexionScreen
import net.jolabs40.tvslim.remote.ui.screens.FichiersScreen
import net.jolabs40.tvslim.remote.ui.screens.JournalScreen
import net.jolabs40.tvslim.remote.ui.screens.MemoireScreen
import net.jolabs40.tvslim.remote.ui.screens.PaquetsScreen

private data class Onglet(val route: String, val titre: Int, val icone: ImageVector)

private val onglets = listOf(
    Onglet("connexion", R.string.tab_connection, Icons.Filled.Cast),
    Onglet("paquets", R.string.tab_packages, Icons.Filled.Inventory2),
    Onglet("applications", R.string.tab_apps, Icons.Filled.Apps),
    Onglet("memoire", R.string.tab_memory, Icons.Filled.Memory),
    Onglet("fichiers", R.string.tab_files, Icons.Filled.Folder),
    Onglet("journal", R.string.tab_log, Icons.Filled.History),
)

/** Depending on the file manager, an APK is typed as an Android package or as plain binary. */
private val TYPES_APK = arrayOf("application/vnd.android.package-archive", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteApp() {
    val modele: RemoteViewModel = hiltViewModel()
    val etat by modele.etat.collectAsStateWithLifecycle()
    val soutienVisible by modele.soutien.visible.collectAsStateWithLifecycle()
    val etatCapture by modele.capture.etat.collectAsStateWithLifecycle()
    val etatApplicationTv by modele.applicationTv.etat.collectAsStateWithLifecycle()
    // Collected outside the Apps tab too: the permissions card uses it to pick an app.
    val etatApplicationsGlobal by modele.applications.etat.collectAsStateWithLifecycle()
    val contexte = LocalContext.current
    val navigation = rememberNavController()
    val pileCourante by navigation.currentBackStackEntryAsState()
    val messages = remember { SnackbarHostState() }

    // An ADB session does not survive TV standby, nor always a long time in another app. On return,
    // silently retry the last TV; failure shows nothing.
    val proprietaire = LocalLifecycleOwner.current
    DisposableEffect(proprietaire) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_START) modele.reprendreConnexion()
        }
        proprietaire.lifecycle.addObserver(observateur)
        onDispose { proprietaire.lifecycle.removeObserver(observateur) }
    }

    // Every action result goes through the same snackbar, then is consumed.
    LaunchedEffect(etat.message) {
        etat.message?.let { texte ->
            messages.showSnackbar(texte)
            modele.effacerMessage()
        }
    }

    etat.confirmation?.let { demande ->
        ConfirmationDialogue(
            confirmation = demande,
            onConfirmer = modele::confirmer,
            onAnnuler = {
                // A declined install leaves the APK copy in the cache; delete it.
                (demande as? Confirmation.Installation)?.let { modele.configuration.abandonnerApk(it.apk) }
                modele.annulerConfirmation()
            },
        )
    }

    var aPropos by rememberSaveable { mutableStateOf(false) }
    if (aPropos) AProposDialogue(onFermer = { aPropos = false })

    etatCapture.derniere?.let { derniere ->
        ApercuCaptureDialogue(
            capture = derniere,
            onPartager = { runCatching { contexte.startActivity(modele.capture.intentionPartage(derniere)) } },
            onFermer = modele.capture::fermer,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Decorative: the name next to it says it all.
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    if (etat.connecte) BoutonCapture(enCours = etatCapture.enCours, onCapturer = modele.capture::capturer)
                    BoutonSoutien()
                    BoutonAPropos(onOuvrir = { aPropos = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
        bottomBar = {
            NavigationBar {
                onglets.forEach { onglet ->
                    val choisi = pileCourante?.destination?.hierarchy
                        ?.any { it.route == onglet.route } == true
                    NavigationBarItem(
                        selected = choisi,
                        onClick = {
                            navigation.navigate(onglet.route) {
                                popUpTo(navigation.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(onglet.icone, contentDescription = null) },
                        label = { Text(stringResource(onglet.titre)) },
                    )
                }
            }
        },
    ) { marges ->
        Column(modifier = Modifier.padding(marges)) {
            // Above every tab, since the action that triggered it may come from any of them.
            BanniereSoutien(
                visible = soutienVisible,
                onSoutenir = modele.soutien::ecarter,
                onDejaFait = modele.soutien::declarerDon,
                onPlusTard = modele.soutien::ecarter,
            )
            NavHost(
                navController = navigation,
                startDestination = "connexion",
                modifier = Modifier.weight(1f),
            ) {
                composable("connexion") {
                    // The Android picker needs no storage permission.
                    val apk = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let(modele.configuration::choisirApk) }
                    ConnexionScreen(
                        etat = etat,
                        onHote = modele::majHote,
                        onPort = modele::majPort,
                        onConnecter = modele::connecter,
                        onDeconnecter = modele::deconnecter,
                        onActualiser = modele::rafraichir,
                        onRedemarrer = modele::demanderRedemarrage,
                        onScan = modele::appliquerScan,
                        onEchecScan = modele::signalerEchecScan,
                        onInstallerLauncher = modele.configuration::installerLauncher,
                        onDefinirAccueil = modele.configuration::definirAccueil,
                        onReprendreDerive = modele.configuration::proposerDerive,
                        onChercher = modele::chercherAppareils,
                        onArreterRecherche = modele::arreterRecherche,
                        onConnecterA = modele::connecterA,
                        actionsPermissions = ActionsPermissions(
                            onPaquet = modele.permissions::majPaquet,
                            onPermission = modele.permissions::majPermission,
                            onLire = modele.permissions::lire,
                            onAccorder = modele.permissions::accorder,
                            onRetirer = modele.permissions::retirer,
                            onChoisirPaquet = modele.permissions::choisirPaquet,
                            onChargerApplications = modele.applications::charger,
                        ),
                        etatApplications = etatApplicationsGlobal,
                        etatApplicationTv = etatApplicationTv,
                        actionsApplicationTv = ActionsApplicationTv(
                            onLire = modele.applicationTv::lire,
                            onInstaller = modele.applicationTv::installer,
                            onAutoriser = modele.applicationTv::autoriser,
                        ),
                        onChoisirApk = { apk.launch(TYPES_APK) },
                        actionsCommande = ActionsCommande(
                            onSaisie = modele.configuration::saisirCommande,
                            onEnvoyer = modele.configuration::envoyerCommande,
                        ),
                        actionsShizuku = ActionsShizuku(
                            onRelancer = modele.configuration::relancerShizuku,
                        ),
                    )
                }
                composable("paquets") {
                    // Files are only read or written where the user picked them.
                    val sauvegarde = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/json"),
                    ) { uri -> uri?.let(modele.configuration::sauvegarder) }
                    val reinjection = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let(modele.configuration::charger) }
                    val inventaire = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("text/markdown"),
                    ) { uri -> uri?.let { modele.configuration.exporterInconnus(it) } }
                    // Same export, then the catalogue form in the browser.
                    val proposition = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("text/markdown"),
                    ) { uri -> uri?.let { modele.configuration.exporterInconnus(it, proposer = true) } }
                    PaquetsScreen(
                        etat = etat,
                        onBasculer = modele::basculerSelection,
                        onProfil = modele::selectionnerProfil,
                        onToutDecocher = modele::toutDeselectionner,
                        onAppliquer = modele::demanderApplication,
                        onReactiver = { modele.reactiver(listOf(it)) },
                        onRecherche = modele::majRecherche,
                        onFiltre = modele::majFiltre,
                        onSauvegarder = { sauvegarde.launch(modele.configuration.nomFichier()) },
                        // Depending on the file manager, a .json file is typed as text or as binary.
                        onReinjecter = {
                            reinjection.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                        onExporterInconnus = { inventaire.launch(modele.configuration.nomExportInconnus()) },
                        onProposerInconnus = { proposition.launch(modele.configuration.nomExportInconnus()) },
                    )
                }
                composable("applications") {
                    val etatApplications by modele.applications.etat.collectAsStateWithLifecycle()
                    val applications = modele.applications
                    ApplicationsScreen(
                        connecte = etat.connecte,
                        etat = etatApplications,
                        actions = remember(applications) {
                            ActionsApplicationsUi(
                                onCharger = applications::charger,
                                onRecherche = applications::majRecherche,
                                onChoisir = applications::choisir,
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
                }
                composable("memoire") {
                    MemoireScreen(
                        etat = etat,
                        onActualiser = modele::rafraichirMemoire,
                        onActualiserStockage = modele::rafraichirStockage,
                        onForcerArret = modele::forcerArret,
                        onRedefinirReference = modele::redefinirReference,
                    )
                }
                composable("fichiers") {
                    val etatFichiers by modele.fichiers.explorateur.etat.collectAsStateWithLifecycle()
                    val enAttente by modele.fichiers.enAttente.collectAsStateWithLifecycle()
                    val explorateur = modele.fichiers.explorateur
                    // The Android picker needs no storage permission, and each document is read only when sent.
                    // Picked items then wait for the user to open their destination.
                    val documents = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenMultipleDocuments(),
                    ) { uris -> modele.fichiers.choisirDocuments(uris) }
                    val dossier = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri -> uri?.let(modele.fichiers::choisirDossier) }
                    FichiersScreen(
                        connecte = etat.connecte,
                        etat = etatFichiers,
                        enAttente = enAttente,
                        actions = ActionsFichiers(
                            onDemarrer = explorateur::demarrer,
                            onOuvrir = explorateur::ouvrir,
                            onRemonter = explorateur::remonter,
                            onActualiser = explorateur::actualiser,
                            onEnvoyerFichiers = { documents.launch(arrayOf("*/*")) },
                            onEnvoyerDossier = { dossier.launch(null) },
                            onCreerDossier = explorateur::creerDossier,
                            onConfirmer = modele.fichiers::confirmer,
                            onAnnulerConfirmation = explorateur::annulerConfirmation,
                            onArreter = explorateur::annulerEnvoi,
                            onEnvoyerIci = modele.fichiers::envoyerIci,
                            onAbandonnerEnvoi = modele.fichiers::abandonnerEnvoi,
                        ),
                    )
                }
                composable("journal") {
                    JournalScreen(
                        etat = etat,
                        onToutRestaurer = modele::demanderRestauration,
                        onExporter = modele::exporterJournal,
                        onAnnulerAction = modele::annulerAction,
                    )
                }
            }
        }
    }
}
