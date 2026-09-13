package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.ProcessusMemoire
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.action_refresh
import net.jolabs40.tvslim.windows.ressources.memory_cached
import net.jolabs40.tvslim.windows.ressources.memory_free
import net.jolabs40.tvslim.windows.ressources.memory_mb
import net.jolabs40.tvslim.windows.ressources.memory_not_connected
import net.jolabs40.tvslim.windows.ressources.memory_processes
import net.jolabs40.tvslim.windows.ressources.memory_reading
import net.jolabs40.tvslim.windows.ressources.memory_stop
import net.jolabs40.tvslim.windows.ressources.memory_title
import net.jolabs40.tvslim.windows.ressources.memory_total
import net.jolabs40.tvslim.windows.ressources.memory_unavailable
import net.jolabs40.tvslim.windows.ressources.memory_used
import net.jolabs40.tvslim.windows.ressources.memory_zram
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.Jauge
import net.jolabs40.tvslim.windows.ui.composants.LigneValeur
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource

/**
 * Répartition de la mémoire et poids de chaque processus.
 *
 * Le pendant du débloat : la liste des paquets dit ce qui est installé, celle-ci ce qui coûte
 * réellement. Sur un téléviseur à 2,45 Go, l'écart entre les deux est tout le sujet. Sur un bureau,
 * la liste des processus a sa propre colonne au lieu de défiler sous les cartes.
 */
@Composable
fun MemoireEcran(
    etat: EtatApp,
    onActualiser: () -> Unit,
    onForcerArret: (String) -> Unit,
    onRedefinirReference: () -> Unit,
) {
    if (!etat.connecte) {
        EcranVide(stringResource(Res.string.memory_not_connected))
        return
    }

    // Première visite sur ce téléviseur : on lit sans attendre qu'on le demande.
    LaunchedEffect(etat.connexion.hote) {
        if (!etat.memoire.renseignee) onActualiser()
    }

    val memoire = etat.memoire

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CarteGain(mesures = etat.mesures, onRedefinirReference = onRedefinirReference)
            CarteMemoire(
                memoire = memoire,
                chargement = etat.chargement,
                tentee = etat.lectureMemoireTentee,
                onActualiser = onActualiser,
            )
        }

        VerticalDivider()

        Column(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
            Text(
                text = stringResource(Res.string.memory_processes, memoire.processus.size),
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val liste = rememberLazyListState()
                LazyColumn(state = liste, modifier = Modifier.fillMaxSize()) {
                    items(memoire.processus, key = { "${it.pid}-${it.nom}" }) { processus ->
                        VueProcessus(
                            processus = processus,
                            nomConnu = etat.catalogue.entrees.firstOrNull { it.paquet == processus.paquet }?.nom,
                            totalKo = memoire.totalKo,
                            onArreter = { onForcerArret(processus.paquet) },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(liste),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun CarteMemoire(
    memoire: RepartitionMemoire,
    chargement: Boolean,
    tentee: Boolean,
    onActualiser: () -> Unit,
) {
    CarteSection(titre = stringResource(Res.string.memory_title), espacement = 6.dp) {
        when {
            memoire.renseignee -> {
                Jauge(memoire.utiliseeKo, memoire.totalKo)
                LigneValeur(stringResource(Res.string.memory_total), mo(memoire.totalKo))
                LigneValeur(stringResource(Res.string.memory_used), mo(memoire.utiliseeKo))
                LigneValeur(stringResource(Res.string.memory_free), mo(memoire.libreKo))
                if (memoire.cacheKo > 0) LigneValeur(stringResource(Res.string.memory_cached), mo(memoire.cacheKo))
                if (memoire.zramKo > 0) LigneValeur(stringResource(Res.string.memory_zram), mo(memoire.zramKo))
            }

            // Le compagnon affiche « lecture en cours » indéfiniment quand la lecture échoue :
            // ici, l'échec se dit, et « Actualiser » retente.
            chargement || !tentee -> TexteSecondaire(stringResource(Res.string.memory_reading))
            else -> Text(
                text = stringResource(Res.string.memory_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onActualiser, enabled = !chargement, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(Res.string.action_refresh))
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
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nomConnu ?: processus.nom,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (nomConnu != null) TexteSecondaire(processus.nom, petit = true)
            }
            Text(
                text = stringResource(Res.string.memory_mb, processus.megaoctets),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (processus.estUneApplication) {
                TextButton(onClick = onArreter) { Text(stringResource(Res.string.memory_stop)) }
            }
        }
        Jauge(processus.kilooctets, totalKo, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun mo(kilooctets: Long): String = stringResource(Res.string.memory_mb, kilooctets / 1024)
