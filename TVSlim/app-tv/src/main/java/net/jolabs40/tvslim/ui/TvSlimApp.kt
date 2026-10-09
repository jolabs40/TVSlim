package net.jolabs40.tvslim.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import net.jolabs40.tvslim.ui.screens.PaquetsScreen
import net.jolabs40.tvslim.ui.screens.ReglagesScreen

object Routes {
    const val ACCUEIL = "accueil"
    const val REGLAGES = "reglages"
    const val APPAIRAGE = "appairage"
    const val PAQUETS = "paquets"
}

@Composable
fun TvSlimApp() {
    // One activity-scoped ViewModel so all screens share the same reading of the TV.
    val modele: TvSlimViewModel = hiltViewModel()
    val etat by modele.etat.collectAsStateWithLifecycle()
    val navigation = rememberNavController()

    // The guard reports drift by notification, so the permission is requested when it is turned on, where
    // the reason is obvious. If denied, the report still shows on the home screen.
    val demandeNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val activerGardien: (Boolean) -> Unit = { actif ->
        modele.definirGardien(actif)
        if (actif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            demandeNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                    onPaquets = { navigation.navigate(Routes.PAQUETS) },
                    onActualiser = modele::rafraichir,
                    onFermerMessage = modele::effacerMessage,
                )
            }
            composable(Routes.PAQUETS) {
                PaquetsScreen(etat = etat)
            }
            composable(Routes.APPAIRAGE) {
                ConnexionTvScreen(etat = etat, onActualiser = modele::rafraichir)
            }
            composable(Routes.REGLAGES) {
                ReglagesScreen(
                    etat = etat,
                    onBasculerReglage = modele::basculerReglage,
                    onGardien = activerGardien,
                    onFermerMessage = modele::effacerMessage,
                )
            }
        }
    }
}
