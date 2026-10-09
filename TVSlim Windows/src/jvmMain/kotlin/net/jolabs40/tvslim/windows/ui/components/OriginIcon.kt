package net.jolabs40.tvslim.windows.ui.components

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
import net.jolabs40.tvslim.device.PackageOrigin
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_android_24
import net.jolabs40.tvslim.windows.resources.baseline_apps_24
import net.jolabs40.tvslim.windows.resources.baseline_factory_24
import net.jolabs40.tvslim.windows.resources.origin_android
import net.jolabs40.tvslim.windows.resources.origin_maker
import net.jolabs40.tvslim.windows.resources.origin_other
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Android robot green, readable on both themes. */
private val ANDROID_GREEN = Color(0xFF3DDC84)

/** Package origin: robot for Android, factory for the device (or chip) maker, grid for the rest. */
@Composable
fun OriginIcon(origin: PackageOrigin, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val tint = when (origin) {
        PackageOrigin.ANDROID -> ANDROID_GREEN
        PackageOrigin.MAKER -> MaterialTheme.colorScheme.tertiary
        PackageOrigin.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        painter = painterResource(
            when (origin) {
                PackageOrigin.ANDROID -> Res.drawable.baseline_android_24
                PackageOrigin.MAKER -> Res.drawable.baseline_factory_24
                PackageOrigin.OTHER -> Res.drawable.baseline_apps_24
            },
        ),
        contentDescription = originLabel(origin),
        modifier = modifier.size(size),
        tint = tint,
    )
}

@Composable
fun originLabel(origin: PackageOrigin): String = stringResource(
    when (origin) {
        PackageOrigin.ANDROID -> Res.string.origin_android
        PackageOrigin.MAKER -> Res.string.origin_maker
        PackageOrigin.OTHER -> Res.string.origin_other
    },
)

/** One-line legend of the origin icons. */
@Composable
fun OriginLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PackageOrigin.ORDER.forEach { origin ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OriginIcon(origin, size = 16.dp)
                Text(
                    text = originLabel(origin),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
