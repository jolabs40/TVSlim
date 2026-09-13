package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.OriginePaquet
import net.jolabs40.tvslim.device.PaquetInconnu
import net.jolabs40.tvslim.remote.R

/** Le vert du robot Android : il se lit sur les deux thèmes. */
private val VERT_ANDROID = Color(0xFF3DDC84)

/**
 * D'où vient un paquet, devant son nom : le robot pour Android, l'usine pour le constructeur de
 * l'appareil (ou de sa puce), une grille pour le reste.
 */
@Composable
fun IconeOrigine(origine: OriginePaquet, modifier: Modifier = Modifier, taille: Dp = 18.dp) {
    Icon(
        imageVector = when (origine) {
            OriginePaquet.ANDROID -> Icons.Filled.Android
            OriginePaquet.CONSTRUCTEUR -> Icons.Filled.Factory
            OriginePaquet.AUTRE -> Icons.Filled.Apps
        },
        contentDescription = libelleOrigine(origine),
        modifier = modifier.size(taille),
        tint = when (origine) {
            OriginePaquet.ANDROID -> VERT_ANDROID
            OriginePaquet.CONSTRUCTEUR -> MaterialTheme.colorScheme.tertiary
            OriginePaquet.AUTRE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun libelleOrigine(origine: OriginePaquet): String = stringResource(
    when (origine) {
        OriginePaquet.ANDROID -> R.string.origin_android
        OriginePaquet.CONSTRUCTEUR -> R.string.origin_maker
        OriginePaquet.AUTRE -> R.string.origin_other
    },
)

/**
 * Sous le catalogue, les paquets livrés avec le téléviseur qu'il ne décrit pas : regroupés par éditeur
 * (« org.droidtv »), chacun avec son origine devinée. En lecture seule — un paquet système inconnu peut
 * porter le tuner ou la télécommande —, mais la liste s'exporte, pour compléter le catalogue.
 */
fun LazyListScope.sectionInconnus(affiches: List<PaquetInconnu>, total: Int, onExporter: () -> Unit) {
    item(key = "inconnus-entete") {
        EnteteInconnus(affiches = affiches.size, total = total, onExporter = onExporter)
    }
    affiches.groupBy { it.famille }.forEach { (famille, membres) ->
        item(key = "inconnus-famille-$famille") {
            Text(
                text = "$famille (${membres.size})",
                modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(membres, key = { "inconnu-${it.paquet}" }) { inconnu -> LigneInconnu(inconnu) }
    }
}

@Composable
private fun EnteteInconnus(affiches: Int, total: Int, onExporter: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text(
            text = stringResource(R.string.unknown_title, affiches),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.unknown_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            OriginePaquet.ORDRE.forEach { origine ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconeOrigine(origine, taille = 16.dp)
                    Text(
                        text = libelleOrigine(origine),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(onClick = onExporter, enabled = total > 0) {
            Text(stringResource(R.string.unknown_export))
        }
    }
}

@Composable
private fun LigneInconnu(inconnu: PaquetInconnu) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconeOrigine(inconnu.origine)
        Text(
            text = inconnu.paquet,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        if (inconnu.etat == EtatPaquet.DESACTIVE) {
            Text(
                text = stringResource(R.string.home_disabled_badge),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
