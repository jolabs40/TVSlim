package net.jolabs40.tvslim.windows.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jolabs40.tvslim.device.Manufacturer
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_home_24
import net.jolabs40.tvslim.windows.resources.launcher_arc
import net.jolabs40.tvslim.windows.resources.launcher_at4k
import net.jolabs40.tvslim.windows.resources.launcher_atvlauncher
import net.jolabs40.tvslim.windows.resources.launcher_atvlauncher_pro
import net.jolabs40.tvslim.windows.resources.launcher_dispatch
import net.jolabs40.tvslim.windows.resources.launcher_emotn
import net.jolabs40.tvslim.windows.resources.launcher_flauncher
import net.jolabs40.tvslim.windows.resources.launcher_halauncher
import net.jolabs40.tvslim.windows.resources.launcher_projectivy
import net.jolabs40.tvslim.windows.resources.launcher_startlight
import net.jolabs40.tvslim.windows.resources.launcher_supertvlauncher
import net.jolabs40.tvslim.windows.resources.launcher_wolf
import net.jolabs40.tvslim.windows.resources.brand_amazon
import net.jolabs40.tvslim.windows.resources.brand_freebox
import net.jolabs40.tvslim.windows.resources.brand_google
import net.jolabs40.tvslim.windows.resources.brand_grundig
import net.jolabs40.tvslim.windows.resources.brand_haier
import net.jolabs40.tvslim.windows.resources.brand_hisense
import net.jolabs40.tvslim.windows.resources.brand_nvidia
import net.jolabs40.tvslim.windows.resources.brand_panasonic
import net.jolabs40.tvslim.windows.resources.brand_philips
import net.jolabs40.tvslim.windows.resources.brand_sharp
import net.jolabs40.tvslim.windows.resources.brand_sony
import net.jolabs40.tvslim.windows.resources.brand_tcl
import net.jolabs40.tvslim.windows.resources.brand_toshiba
import net.jolabs40.tvslim.windows.resources.brand_xiaomi
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Launcher logos, keyed by their id in the shared catalogue.
 *
 * Names and packages live in the catalogue; images are resources of each app. An id without a logo falls
 * back to a neutral icon, so an unknown launcher is still shown.
 */
val LOGOS_LAUNCHERS: Map<String, DrawableResource> = mapOf(
    "startlight" to Res.drawable.launcher_startlight,
    "projectivy" to Res.drawable.launcher_projectivy,
    "flauncher" to Res.drawable.launcher_flauncher,
    "atvlauncher" to Res.drawable.launcher_atvlauncher,
    "atvlauncher_pro" to Res.drawable.launcher_atvlauncher_pro,
    "at4k" to Res.drawable.launcher_at4k,
    "wolf" to Res.drawable.launcher_wolf,
    "supertvlauncher" to Res.drawable.launcher_supertvlauncher,
    "halauncher" to Res.drawable.launcher_halauncher,
    "dispatch" to Res.drawable.launcher_dispatch,
    "emotn" to Res.drawable.launcher_emotn,
    "arc" to Res.drawable.launcher_arc,
)

/**
 * Maker logos: ten TV brands, five box brands (Xiaomi is both). Thomson, Nokia and Skyworth are recognized
 * without a logo and spelled out on their plate.
 */
val MANUFACTURER_LOGOS: Map<Manufacturer, DrawableResource> = mapOf(
    Manufacturer.TCL to Res.drawable.brand_tcl,
    Manufacturer.HISENSE to Res.drawable.brand_hisense,
    Manufacturer.PHILIPS to Res.drawable.brand_philips,
    Manufacturer.SONY to Res.drawable.brand_sony,
    Manufacturer.XIAOMI to Res.drawable.brand_xiaomi,
    Manufacturer.SHARP to Res.drawable.brand_sharp,
    Manufacturer.GRUNDIG to Res.drawable.brand_grundig,
    Manufacturer.TOSHIBA to Res.drawable.brand_toshiba,
    Manufacturer.HAIER to Res.drawable.brand_haier,
    Manufacturer.PANASONIC to Res.drawable.brand_panasonic,
    Manufacturer.NVIDIA to Res.drawable.brand_nvidia,
    Manufacturer.GOOGLE to Res.drawable.brand_google,
    Manufacturer.AMAZON to Res.drawable.brand_amazon,
    Manufacturer.FREEBOX to Res.drawable.brand_freebox,
)

/** Launcher icon, rounded as on the TV. */
@Composable
fun LogoLauncher(id: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.22f)
    val logo = id?.let { LOGOS_LAUNCHERS[it] }
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(Res.drawable.baseline_home_24),
                contentDescription = null,
                modifier = Modifier.size(size * 0.55f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Device brand on a white plate, because black lettering (Sony, NVIDIA, Amazon) would vanish on the dark
 * theme. A brand without a logo is spelled out.
 */
@Composable
fun BrandPlate(manufacturer: Manufacturer, height: Dp = 32.dp, modifier: Modifier = Modifier) {
    val logo = MANUFACTURER_LOGOS[manufacturer]
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(height * 0.25f),
        modifier = modifier.height(height),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = height * 0.35f, vertical = height * 0.2f),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                val painter = painterResource(logo)
                val size = painter.intrinsicSize
                val proportion = if (size.isSpecified && size.height > 0f) size.width / size.height else 3f
                Image(
                    painter = painter,
                    contentDescription = manufacturer.displayName,
                    modifier = Modifier.height(height * 0.6f).aspectRatio(proportion),
                )
            } else {
                Text(
                    text = manufacturer.displayName,
                    color = Color(0xFF1B1F23),
                    fontWeight = FontWeight.Bold,
                    fontSize = (height.value * 0.42f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}
