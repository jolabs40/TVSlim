package net.jolabs40.tvslim.windows.ui.composants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.OriginePaquet
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_android_24
import net.jolabs40.tvslim.windows.ressources.baseline_apps_24
import net.jolabs40.tvslim.windows.ressources.baseline_factory_24
import net.jolabs40.tvslim.windows.ressources.origin_android
import net.jolabs40.tvslim.windows.ressources.origin_maker
import net.jolabs40.tvslim.windows.ressources.origin_other
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Android robot green, readable on both themes. */
private val VERT_ANDROID = Color(0xFF3DDC84)

/** Package origin: robot for Android, factory for the device (or chip) maker, grid for the rest. */
@Composable
fun IconeOrigine(origine: OriginePaquet, modifier: Modifier = Modifier, taille: Dp = 18.dp) {
    val teinte = when (origine) {
        OriginePaquet.ANDROID -> VERT_ANDROID
        OriginePaquet.CONSTRUCTEUR -> MaterialTheme.colorScheme.tertiary
        OriginePaquet.AUTRE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        painter = painterResource(
            when (origine) {
                OriginePaquet.ANDROID -> Res.drawable.baseline_android_24
                OriginePaquet.CONSTRUCTEUR -> Res.drawable.baseline_factory_24
                OriginePaquet.AUTRE -> Res.drawable.baseline_apps_24
            },
        ),
        contentDescription = libelleOrigine(origine),
        modifier = modifier.size(taille),
        tint = teinte,
    )
}

@Composable
fun libelleOrigine(origine: OriginePaquet): String = stringResource(
    when (origine) {
        OriginePaquet.ANDROID -> Res.string.origin_android
        OriginePaquet.CONSTRUCTEUR -> Res.string.origin_maker
        OriginePaquet.AUTRE -> Res.string.origin_other
    },
)

/** One-line legend of the origin icons. */
@Composable
fun LegendeOrigines(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OriginePaquet.ORDRE.forEach { origine ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconeOrigine(origine, taille = 16.dp)
                Text(
                    text = libelleOrigine(origine),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
