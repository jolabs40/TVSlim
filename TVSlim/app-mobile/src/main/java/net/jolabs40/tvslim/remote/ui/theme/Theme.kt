package net.jolabs40.tvslim.remote.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val vertMenthe = Color(0xFF2E7D5B)
private val vertClair = Color(0xFF7FD1AE)

private val schemaClair = lightColorScheme(primary = vertMenthe, secondary = Color(0xFF3F6B8A))
private val schemaSombre = darkColorScheme(primary = vertClair, secondary = Color(0xFF8AB4F8))

/** Couleurs des pastilles de risque, hors palette Material pour rester lisibles d'un coup d'œil. */
object CouleursRisque {
    val aucun = Color(0xFF2E9E6B)
    val faible = Color(0xFF9AA83A)
    val moyen = Color(0xFFCC8A2E)
    val eleve = Color(0xFFD1443F)
}

@Composable
fun TvSlimRemoteTheme(
    sombre: Boolean = isSystemInDarkTheme(),
    contenu: @Composable () -> Unit,
) {
    val contexte = LocalContext.current
    val couleurs = when {
        // Material You quand l'appareil le permet : l'app se fond dans le téléphone.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (sombre) dynamicDarkColorScheme(contexte) else dynamicLightColorScheme(contexte)

        sombre -> schemaSombre
        else -> schemaClair
    }
    MaterialTheme(colorScheme = couleurs, content = contenu)
}
