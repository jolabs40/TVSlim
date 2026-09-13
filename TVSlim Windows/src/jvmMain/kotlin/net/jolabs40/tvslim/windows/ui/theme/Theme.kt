package net.jolabs40.tvslim.windows.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * Le vert menthe et le bleu du compagnon, étendus à toute la palette. Le téléphone s'en remet à
 * Material You ; un bureau n'en a pas, et les rôles laissés par défaut tireraient vers le mauve
 * de Material — d'où les surfaces teintées de vert, en clair comme en sombre.
 */

private val schemaClair = lightColorScheme(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB3EFD0),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF3F6B8A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCBE6FF),
    onSecondaryContainer = Color(0xFF001E30),
    background = Color(0xFFF6FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF6FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C1),
    inverseSurface = Color(0xFF2C312E),
    inverseOnSurface = Color(0xFFEDF2EE),
    surfaceBright = Color(0xFFF6FBF7),
    surfaceDim = Color(0xFFD6DBD7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5F1),
    surfaceContainer = Color(0xFFEAEFEB),
    surfaceContainerHigh = Color(0xFFE4EAE5),
    surfaceContainerHighest = Color(0xFFDFE4DF),
)

private val schemaSombre = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFF9CF2C9),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF1F4A6E),
    onSecondaryContainer = Color(0xFFCBE6FF),
    background = Color(0xFF101418),
    onBackground = Color(0xFFDEE4DF),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFDEE4DF),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
    inverseSurface = Color(0xFFDEE4DF),
    inverseOnSurface = Color(0xFF2C312E),
    surfaceBright = Color(0xFF363A3E),
    surfaceDim = Color(0xFF101418),
    surfaceContainerLowest = Color(0xFF0B0F12),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1C2024),
    surfaceContainerHigh = Color(0xFF262B2E),
    surfaceContainerHighest = Color(0xFF313539),
)

/** Couleurs des pastilles de risque, hors palette Material pour rester lisibles d'un coup d'œil. */
object CouleursRisque {
    val aucun = Color(0xFF2E9E6B)
    val faible = Color(0xFF9AA83A)
    val moyen = Color(0xFFCC8A2E)
    val eleve = Color(0xFFD1443F)
}

@Composable
fun TvSlimTheme(
    sombre: Boolean = isSystemInDarkTheme(),
    contenu: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (sombre) schemaSombre else schemaClair, content = contenu)
}
