package net.jolabs40.tvslim.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Palette sombre : sur un téléviseur, tout le reste éblouit dans une pièce sombre. */
private val schemaSombre = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    onPrimary = Color(0xFF05281B),
    secondary = Color(0xFF8AB4F8),
    onSecondary = Color(0xFF0A2540),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE6E9EC),
    surface = Color(0xFF1A1F25),
    onSurface = Color(0xFFE6E9EC),
    surfaceVariant = Color(0xFF262C33),
    onSurfaceVariant = Color(0xFFB9C0C7),
    error = Color(0xFFF2837F),
    onError = Color(0xFF3B0907),
)

/** Couleurs des pastilles de risque, hors palette Material pour rester lisibles au premier coup d'œil. */
object CouleursRisque {
    val aucun = Color(0xFF7FD1AE)
    val faible = Color(0xFFD8E07F)
    val moyen = Color(0xFFE0B57F)
    val eleve = Color(0xFFF2837F)
}

@Composable
fun TvSlimTheme(contenu: @Composable () -> Unit) {
    // Hors d'une Surface, `Text` prend la couleur par défaut de `LocalContentColor`, qui est
    // sombre : sans ce fournisseur, les titres seraient noirs sur fond noir. Les Surface
    // fournissent la leur plus bas dans l'arbre et ne sont donc pas affectées.
    MaterialTheme(colorScheme = schemaSombre) {
        CompositionLocalProvider(
            LocalContentColor provides schemaSombre.onBackground,
            content = contenu,
        )
    }
}
