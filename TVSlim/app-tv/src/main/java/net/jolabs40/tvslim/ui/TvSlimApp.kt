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
import net.jolabs40.tvslim.ui.screens.HomeScreen
import net.jolabs40.tvslim.ui.screens.TvConnectionScreen
import net.jolabs40.tvslim.ui.screens.PackagesScreen
import net.jolabs40.tvslim.ui.screens.SettingsScreen

object Routes {
    const val HOME = "accueil"
    const val SETTINGS = "reglages"
    const val PAIRING = "appairage"
    const val PACKAGES = "paquets"
}

@Composable
fun TvSlimApp() {
    // One activity-scoped ViewModel so all screens share the same reading of the TV.
    val model: TvSlimViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val navigation = rememberNavController()

    // The guard reports drift by notification, so the permission is requested when it is turned on, where
    // the reason is obvious. If denied, the report still shows on the home screen.
    val notificationRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val enableGuardian: (Boolean) -> Unit = { active ->
        model.setGuardianEnabled(active)
        if (active && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 48.dp, vertical = 32.dp),
    ) {
        NavHost(navController = navigation, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(
                    state = state,
                    onSettings = { navigation.navigate(Routes.SETTINGS) },
                    onPairing = { navigation.navigate(Routes.PAIRING) },
                    onPackages = { navigation.navigate(Routes.PACKAGES) },
                    onRefresh = model::refresh,
                    onCloseMessage = model::clearMessage,
                )
            }
            composable(Routes.PACKAGES) {
                PackagesScreen(state = state)
            }
            composable(Routes.PAIRING) {
                TvConnectionScreen(state = state, onRefresh = model::refresh)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    state = state,
                    onToggleSetting = model::toggleSetting,
                    onGuardian = enableGuardian,
                    onCloseMessage = model::clearMessage,
                )
            }
        }
    }
}
