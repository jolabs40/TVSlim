package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.OriginePaquet
import net.jolabs40.tvslim.device.PaquetInconnu
import net.jolabs40.tvslim.remote.R

/** Android robot green, readable in both themes. */
private val VERT_ANDROID = Color(0xFF3DDC84)

/** Package origin: robot for Android, factory for the device (or chip) maker, grid for anything else. */
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
 * Preinstalled packages the catalogue does not describe, grouped by publisher (`org.droidtv`) with a guessed origin.
 *
 * Read-only, since an unknown system package may drive the tuner or the remote. The list can be exported, and
 * proposed through the GitHub form when it holds a maker package.
 */
fun LazyListScope.sectionInconnus(
    affiches: List<PaquetInconnu>,
    total: Int,
    onExporter: () -> Unit,
    /** Null when the catalogue misses no maker package, so there is nothing worth proposing. */
    onProposer: (() -> Unit)?,
) {
    item(key = "inconnus-entete") {
        EnteteInconnus(affiches = affiches.size, total = total, onExporter = onExporter, onProposer = onProposer)
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
private fun EnteteInconnus(affiches: Int, total: Int, onExporter: () -> Unit, onProposer: (() -> Unit)?) {
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
        // Two buttons share the phone's width; alone, the export button keeps its own.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val partage = if (onProposer != null) Modifier.weight(1f) else Modifier
            OutlinedButton(
                onClick = onExporter,
                enabled = total > 0,
                modifier = partage,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.unknown_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            onProposer?.let { proposer ->
                Button(onClick = proposer, modifier = partage, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.unknown_propose), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
