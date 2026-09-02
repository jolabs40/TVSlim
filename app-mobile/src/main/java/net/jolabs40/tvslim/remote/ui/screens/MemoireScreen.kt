package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.ProcessusMemoire
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatRemote

/**
 * Répartition de la mémoire et poids de chaque processus.
 *
 * C'est le pendant du débloat : la liste des paquets dit ce qui est installé, celle-ci dit ce
 * qui coûte réellement. Sur un téléviseur à 2,45 Go, l'écart entre les deux est tout le sujet —
 * une application désactivée ne pèse rien, une application anodine qui tourne en fond peut
 * peser cent mégaoctets.
 */
@Composable
fun MemoireScreen(
    etat: EtatRemote,
    onActualiser: () -> Unit,
    onForcerArret: (String) -> Unit,
) {
    if (!etat.connecte) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.packages_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Première visite : on lit sans attendre qu'on le demande.
    LaunchedEffect(etat.connexion.hote) {
        if (!etat.memoire.renseignee) onActualiser()
    }

    val memoire = etat.memoire

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )

                if (memoire.renseignee) {
                    Jauge(memoire.utiliseeKo, memoire.totalKo)
                    Ligne(stringResource(R.string.memory_total), mo(memoire.totalKo))
                    Ligne(stringResource(R.string.memory_used), mo(memoire.utiliseeKo))
                    Ligne(stringResource(R.string.memory_free), mo(memoire.libreKo))
                    if (memoire.cacheKo > 0) {
                        Ligne(stringResource(R.string.memory_cached), mo(memoire.cacheKo))
                    }
                    if (memoire.zramKo > 0) {
                        Ligne(stringResource(R.string.memory_zram), mo(memoire.zramKo))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.memory_reading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(onClick = onActualiser, enabled = !etat.chargement) {
                    Text(stringResource(R.string.action_refresh))
                }
            }
        }

        HorizontalDivider()

        Text(
            text = stringResource(R.string.memory_processes, memoire.processus.size),
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(memoire.processus, key = { "${it.pid}-${it.nom}" }) { processus ->
                VueProcessus(
                    processus = processus,
                    nomConnu = etat.catalogue.entrees
                        .firstOrNull { it.paquet == processus.paquet }
                        ?.nom,
                    totalKo = memoire.totalKo,
                    onArreter = { onForcerArret(processus.paquet) },
                )
            }
        }
    }
}

@Composable
private fun VueProcessus(
    processus: ProcessusMemoire,
    nomConnu: String?,
    totalKo: Long,
    onArreter: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nomConnu ?: processus.nom,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (nomConnu != null) {
                    Text(
                        text = processus.nom,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.memory_mb, processus.megaoctets),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (processus.estUneApplication) {
                TextButton(onClick = onArreter) {
                    Text(stringResource(R.string.memory_stop))
                }
            }
        }
        Jauge(processus.kilooctets, totalKo)
    }
}

/** Une barre proportionnelle : plus parlante qu'un nombre isolé. */
@Composable
private fun Jauge(valeur: Long, total: Long) {
    val fraction = if (total > 0) (valeur.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun Ligne(libelle: String, valeur: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = libelle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = valeur, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun mo(kilooctets: Long): String = "${kilooctets / 1024} Mo"
