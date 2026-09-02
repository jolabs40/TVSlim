package net.jolabs40.tvslim.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import net.jolabs40.tvslim.ui.screens.AccueilScreen
import net.jolabs40.tvslim.ui.screens.JournalScreen
import net.jolabs40.tvslim.ui.screens.PaquetsScreen
import net.jolabs40.tvslim.ui.screens.ReglagesScreen
import net.jolabs40.tvslim.ui.screens.ShizukuScreen

object Routes {
    const val ACCUEIL = "accueil"
    const val PAQUETS = "paquets"
    const val REGLAGES = "reglages"
    const val JOURNAL = "journal"
    const val SHIZUKU = "shizuku"
}

@Composable
fun TvSlimApp() {
    // Un seul ViewModel, tenu au niveau de l'activité : les quatre écrans partagent la même
    // photographie de l'appareil et le même journal.
    val modele: TvSlimViewModel = hiltViewModel()
    val etat by modele.etat.collectAsStateWithLifecycle()
    val navigation = rememberNavController()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        NavHost(navController = navigation, startDestination = Routes.ACCUEIL) {
            composable(Routes.ACCUEIL) {
                AccueilScreen(
                    etat = etat,
                    onPaquets = { navigation.navigate(Routes.PAQUETS) },
                    onReglages = { navigation.navigate(Routes.REGLAGES) },
                    onJournal = { navigation.navigate(Routes.JOURNAL) },
                    onActualiser = modele::rafraichir,
                    onAutoriser = modele::demanderAutorisation,
                    onInstallerShizuku = { navigation.navigate(Routes.SHIZUKU) },
                    onFermerMessage = modele::effacerMessage,
                )
            }
            composable(Routes.PAQUETS) {
                PaquetsScreen(
                    etat = etat,
                    onBasculer = modele::basculerSelection,
                    onProfil = modele::selectionnerProfil,
                    onToutDecocher = modele::toutDeselectionner,
                    onAppliquer = modele::appliquerSelection,
                    onReactiver = { modele.reactiver(listOf(it)) },
                    onFermerMessage = modele::effacerMessage,
                )
            }
            composable(Routes.REGLAGES) {
                ReglagesScreen(
                    etat = etat,
                    onBasculerReglage = modele::basculerReglage,
                    onGardien = modele::definirGardien,
                    onAutoriser = modele::demanderAutorisation,
                    onInstallerShizuku = { navigation.navigate(Routes.SHIZUKU) },
                    onFermerMessage = modele::effacerMessage,
                )
            }
            composable(Routes.SHIZUKU) {
                ShizukuScreen(
                    etat = etat,
                    onActualiser = modele::rafraichir,
                    onInstaller = modele::installerShizuku,
                    intentionSourcesInconnues = modele::intentionSourcesInconnues,
                )
            }
            composable(Routes.JOURNAL) {
                JournalScreen(
                    etat = etat,
                    onToutRestaurer = modele::toutRestaurer,
                    onExporter = modele::exporterJournal,
                    onFermerMessage = modele::effacerMessage,
                )
            }
        }
    }
}
