package net.jolabs40.tvslim.remote.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Inventory2
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
import net.jolabs40.tvslim.remote.ui.screens.ConfirmationDialogue
import net.jolabs40.tvslim.remote.ui.screens.ConnexionScreen
import net.jolabs40.tvslim.remote.ui.screens.JournalScreen
import net.jolabs40.tvslim.remote.ui.screens.MemoireScreen
import net.jolabs40.tvslim.remote.ui.screens.PaquetsScreen

private data class Onglet(val route: String, val titre: Int, val icone: ImageVector)

private val onglets = listOf(
    Onglet("connexion", R.string.tab_connection, Icons.Filled.Cast),
    Onglet("paquets", R.string.tab_packages, Icons.Filled.Inventory2),
    Onglet("memoire", R.string.tab_memory, Icons.Filled.Memory),
    Onglet("journal", R.string.tab_log, Icons.Filled.History),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteApp() {
    val modele: RemoteViewModel = hiltViewModel()
    val etat by modele.etat.collectAsStateWithLifecycle()
    val navigation = rememberNavController()
    val pileCourante by navigation.currentBackStackEntryAsState()
    val messages = remember { SnackbarHostState() }

    // Une session ADB ne survit pas à la veille du téléviseur, ni forcément à un long
    // passage dans une autre application. Au retour à l'écran, on retente le dernier
    // téléviseur sans rien demander ; l'échec reste silencieux.
    val proprietaire = LocalLifecycleOwner.current
    DisposableEffect(proprietaire) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_START) modele.reprendreConnexion()
        }
        proprietaire.lifecycle.addObserver(observateur)
        onDispose { proprietaire.lifecycle.removeObserver(observateur) }
    }

    // Chaque retour d'action passe par la même bannière, puis est consommé.
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
            onAnnuler = modele::annulerConfirmation,
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
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
        NavHost(
            navController = navigation,
            startDestination = "connexion",
            modifier = Modifier.padding(marges),
        ) {
            composable("connexion") {
                ConnexionScreen(
                    etat = etat,
                    onHote = modele::majHote,
                    onPort = modele::majPort,
                    onConnecter = modele::connecter,
                    onDeconnecter = modele::deconnecter,
                    onActualiser = modele::rafraichir,
                    onScan = modele::appliquerScan,
                    onEchecScan = modele::signalerEchecScan,
                    onInstallerLauncher = modele::installerLauncher,
                    onChercher = modele::chercherAppareils,
                    onArreterRecherche = modele::arreterRecherche,
                    onConnecterA = modele::connecterA,
                    actionsPermissions = ActionsPermissions(
                        onPaquet = modele.permissions::majPaquet,
                        onPermission = modele.permissions::majPermission,
                        onLire = modele.permissions::lire,
                        onAccorder = modele.permissions::accorder,
                        onRetirer = modele.permissions::retirer,
                    ),
                )
            }
            composable("paquets") {
                PaquetsScreen(
                    etat = etat,
                    onBasculer = modele::basculerSelection,
                    onProfil = modele::selectionnerProfil,
                    onToutDecocher = modele::toutDeselectionner,
                    onAppliquer = modele::demanderApplication,
                    onReactiver = { modele.reactiver(listOf(it)) },
                    onRecherche = modele::majRecherche,
                    onFiltre = modele::majFiltre,
                )
            }
            composable("memoire") {
                MemoireScreen(
                    etat = etat,
                    onActualiser = modele::rafraichirMemoire,
                    onForcerArret = modele::forcerArret,
                    onRedefinirReference = modele::redefinirReference,
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
