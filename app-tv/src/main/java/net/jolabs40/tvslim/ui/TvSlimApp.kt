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
import net.jolabs40.tvslim.ui.screens.ConnexionTvScreen
import net.jolabs40.tvslim.ui.screens.ReglagesScreen

object Routes {
    const val ACCUEIL = "accueil"
    const val REGLAGES = "reglages"
    const val APPAIRAGE = "appairage"
}

@Composable
fun TvSlimApp() {
    // Un seul ViewModel, tenu au niveau de l'activité : les deux écrans partagent la même
    // photographie du téléviseur.
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
                    onReglages = { navigation.navigate(Routes.REGLAGES) },
                    onAppairage = { navigation.navigate(Routes.APPAIRAGE) },
                    onActualiser = modele::rafraichir,
                    onFermerMessage = modele::effacerMessage,
                )
            }
            composable(Routes.APPAIRAGE) {
                ConnexionTvScreen(etat = etat, onActualiser = modele::rafraichir)
            }
            composable(Routes.REGLAGES) {
                ReglagesScreen(
                    etat = etat,
                    onBasculerReglage = modele::basculerReglage,
                    onGardien = modele::definirGardien,
                    onFermerMessage = modele::effacerMessage,
                )
            }
        }
    }
}
