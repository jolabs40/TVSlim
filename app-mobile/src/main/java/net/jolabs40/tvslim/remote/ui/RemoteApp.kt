package net.jolabs40.tvslim.remote.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.History
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.screens.ConnexionScreen
import net.jolabs40.tvslim.remote.ui.screens.JournalScreen
import net.jolabs40.tvslim.remote.ui.screens.PaquetsScreen

private data class Onglet(val route: String, val titre: Int, val icone: ImageVector)

private val onglets = listOf(
    Onglet("connexion", R.string.tab_connection, Icons.Filled.Cast),
    Onglet("paquets", R.string.tab_packages, Icons.Filled.Inventory2),
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

    // Chaque retour d'action passe par la même bannière, puis est consommé.
    LaunchedEffect(etat.message) {
        etat.message?.let { texte ->
            messages.showSnackbar(texte)
            modele.effacerMessage()
        }
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
                )
            }
            composable("paquets") {
                PaquetsScreen(
                    etat = etat,
                    onBasculer = modele::basculerSelection,
                    onProfil = modele::selectionnerProfil,
                    onToutDecocher = modele::toutDeselectionner,
                    onAppliquer = modele::appliquerSelection,
                    onReactiver = { modele.reactiver(listOf(it)) },
                )
            }
            composable("journal") {
                JournalScreen(
                    etat = etat,
                    onToutRestaurer = modele::toutRestaurer,
                    onExporter = modele::exporterJournal,
                )
            }
        }
    }
}
