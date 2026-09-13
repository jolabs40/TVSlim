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
import net.jolabs40.tvslim.device.Fabricant
import net.jolabs40.tvslim.remote.R

/**
 * Les logos des launchers, par l'identifiant que leur donne le catalogue partagé — le pendant de
 * ceux de l'application Windows. Noms et paquets vivent dans le catalogue ; les images sont des
 * ressources de chaque application. Un identifiant sans logo retombe sur une icône neutre.
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
 * Les logos des fabricants : dix de téléviseurs, cinq de box (Xiaomi est des deux). Thomson, Nokia et
 * Skyworth sont reconnus sans logo, et s'écrivent en toutes lettres sur leur plaque.
 */
val LOGOS_FABRICANTS: Map<Fabricant, Int> = mapOf(
    Fabricant.TCL to R.drawable.marque_tcl,
    Fabricant.HISENSE to R.drawable.marque_hisense,
    Fabricant.PHILIPS to R.drawable.marque_philips,
    Fabricant.SONY to R.drawable.marque_sony,
    Fabricant.XIAOMI to R.drawable.marque_xiaomi,
    Fabricant.SHARP to R.drawable.marque_sharp,
    Fabricant.GRUNDIG to R.drawable.marque_grundig,
    Fabricant.TOSHIBA to R.drawable.marque_toshiba,
    Fabricant.HAIER to R.drawable.marque_haier,
    Fabricant.PANASONIC to R.drawable.marque_panasonic,
    Fabricant.NVIDIA to R.drawable.marque_nvidia,
    Fabricant.GOOGLE to R.drawable.marque_google,
    Fabricant.AMAZON to R.drawable.marque_amazon,
    Fabricant.FREEBOX to R.drawable.marque_freebox,
)

/** L'icône d'un launcher, arrondie comme sur le téléviseur. */
@Composable
fun LogoLauncher(id: String?, taille: Dp = 40.dp, modifier: Modifier = Modifier) {
    val forme = RoundedCornerShape(taille * 0.22f)
    val logo = id?.let { LOGOS_LAUNCHERS[it] }
    if (logo != null) {
        Image(
            painter = painterResource(logo),
            contentDescription = null,
            modifier = modifier.size(taille).clip(forme),
        )
    } else {
        Box(
            modifier = modifier.size(taille).background(MaterialTheme.colorScheme.surfaceVariant, forme),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = null,
                modifier = Modifier.size(taille * 0.55f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * La marque d'un appareil, sur une plaque claire : les lettrages noirs — Sony, NVIDIA, Amazon —
 * disparaîtraient sur le thème sombre, et la plaque garde la même tenue partout. Une marque reconnue sans logo s'y
 * écrit en toutes lettres.
 */
@Composable
fun PlaqueMarque(fabricant: Fabricant, hauteur: Dp = 32.dp, modifier: Modifier = Modifier) {
    val logo = LOGOS_FABRICANTS[fabricant]
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(hauteur * 0.25f),
        modifier = modifier.height(hauteur),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = hauteur * 0.35f, vertical = hauteur * 0.2f),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                val peintre = painterResource(logo)
                val taille = peintre.intrinsicSize
                val proportion = if (taille.isSpecified && taille.height > 0f) taille.width / taille.height else 3f
                Image(
                    painter = peintre,
                    contentDescription = fabricant.nom,
                    modifier = Modifier.height(hauteur * 0.6f).aspectRatio(proportion),
                )
            } else {
                Text(
                    text = fabricant.nom,
                    color = Color(0xFF1B1F23),
                    fontWeight = FontWeight.Bold,
                    fontSize = (hauteur.value * 0.42f).sp,
                    maxLines = 1,
                )
            }
        }
    }
}
