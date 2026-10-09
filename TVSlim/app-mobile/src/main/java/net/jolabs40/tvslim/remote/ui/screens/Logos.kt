package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.jolabs40.tvslim.device.Manufacturer
import net.jolabs40.tvslim.remote.R

/**
 * Launcher logos by catalogue id, mirroring the Windows app. Names and packages live in the shared catalogue, images in
 * each app's resources. An id without a logo falls back to a neutral icon.
 */
val LOGOS_LAUNCHERS: Map<String, Int> = mapOf(
    "startlight" to R.drawable.launcher_startlight,
    "projectivy" to R.drawable.launcher_projectivy,
    "flauncher" to R.drawable.launcher_flauncher,
    "atvlauncher" to R.drawable.launcher_atvlauncher,
    "atvlauncher_pro" to R.drawable.launcher_atvlauncher_pro,
    "at4k" to R.drawable.launcher_at4k,
    "wolf" to R.drawable.launcher_wolf,
    "supertvlauncher" to R.drawable.launcher_supertvlauncher,
    "halauncher" to R.drawable.launcher_halauncher,
    "dispatch" to R.drawable.launcher_dispatch,
    "emotn" to R.drawable.launcher_emotn,
    "arc" to R.drawable.launcher_arc,
)

/**
 * Ten TV brands and five box brands (Xiaomi is both). Thomson, Nokia and Skyworth are recognized without a logo and
 * spelled out on their plate.
 */
val MANUFACTURER_LOGOS: Map<Manufacturer, Int> = mapOf(
    Manufacturer.TCL to R.drawable.brand_tcl,
    Manufacturer.HISENSE to R.drawable.brand_hisense,
    Manufacturer.PHILIPS to R.drawable.brand_philips,
    Manufacturer.SONY to R.drawable.brand_sony,
    Manufacturer.XIAOMI to R.drawable.brand_xiaomi,
    Manufacturer.SHARP to R.drawable.brand_sharp,
    Manufacturer.GRUNDIG to R.drawable.brand_grundig,
    Manufacturer.TOSHIBA to R.drawable.brand_toshiba,
    Manufacturer.HAIER to R.drawable.brand_haier,
    Manufacturer.PANASONIC to R.drawable.brand_panasonic,
    Manufacturer.NVIDIA to R.drawable.brand_nvidia,
    Manufacturer.GOOGLE to R.drawable.brand_google,
    Manufacturer.AMAZON to R.drawable.brand_amazon,
    Manufacturer.FREEBOX to R.drawable.brand_freebox,
)

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
                imageVector = Icons.Filled.Home,
                contentDescription = null,
                modifier = Modifier.size(size * 0.55f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Brand logo on a white plate, since black lettering (Sony, NVIDIA, Amazon) would vanish in the dark theme. A brand
 * without a logo is spelled out.
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
