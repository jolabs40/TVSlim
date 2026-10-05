package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R

/**
 * Les outils rares de l'onglet Téléviseur — permissions privilégiées, installation d'un APK, commande ADB,
 * relance de Shizuku —, repliés sous un seul titre. Quatre cartes identiques en bas de l'écran pesaient autant
 * que l'essentiel, sans rapport avec le débloat (remarque de l'utilisateur, 2026-10-05).
 *
 * Repliés par défaut ; une opération en cours ([occupe]) les tient dépliés, pour ne jamais cacher une
 * avancée ni un résultat qui arrive.
 */
@Composable
fun OutilsAvances(occupe: Boolean, contenu: @Composable ColumnScope.() -> Unit) {
    var deplie by rememberSaveable { mutableStateOf(false) }
    val ouvert = deplie || occupe
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            onClick = { deplie = !ouvert },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.advanced_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.advanced_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(if (ouvert) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
        }
        AnimatedVisibility(visible = ouvert) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = contenu)
        }
    }
}
