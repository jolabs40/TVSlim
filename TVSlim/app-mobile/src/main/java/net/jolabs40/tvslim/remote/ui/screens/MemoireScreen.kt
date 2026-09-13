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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.ProcessusMemoire
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.device.StockageApplication
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatRemote
import java.util.Locale

/** Ce que l'onglet montre : la mémoire vive, ou le stockage interne. */
private enum class VueMemoire { VIVE, STOCKAGE }

/**
 * Mémoire vive et stockage, d'un interrupteur.
 *
 * C'est le pendant du débloat : la liste des paquets dit ce qui est installé, celle-ci dit ce
 * qui coûte réellement. Sur un téléviseur à 2,45 Go, l'écart entre les deux est tout le sujet —
 * une application désactivée ne pèse rien, une application anodine qui tourne en fond peut
 * peser cent mégaoctets. Le stockage répond à l'autre question : ce qui occupe la place.
 */
@Composable
fun MemoireScreen(
    etat: EtatRemote,
    onActualiser: () -> Unit,
    onActualiserStockage: () -> Unit,
    onForcerArret: (String) -> Unit,
    onRedefinirReference: () -> Unit,
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

    var vue by rememberSaveable { mutableStateOf(VueMemoire.VIVE) }

    // Première visite : on lit sans attendre qu'on le demande.
    LaunchedEffect(etat.connexion.hote) {
        if (!etat.memoire.renseignee) onActualiser()
    }
    LaunchedEffect(etat.connexion.hote, vue) {
        if (vue == VueMemoire.STOCKAGE && !etat.stockage.renseignee) onActualiserStockage()
    }

    val memoire = etat.memoire
    val stockage = etat.stockage
    // Les centaines de paquets système de quelques Ko n'apprennent rien : on liste à partir d'un Mo.
    val applications = stockage.applications.filter { it.totalOctets >= UN_MO }

    // Tout défile ensemble : sur un téléphone, deux cartes fixes ne laisseraient presque rien
    // à la liste, qui est pourtant le cœur de cet écran.
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            ChoixVue(vue = vue, onVue = { vue = it })
        }

        when (vue) {
            VueMemoire.VIVE -> {
                item {
                    CarteGain(mesures = etat.mesures, onRedefinirReference = onRedefinirReference)
                }

                item {
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
                }

                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.memory_processes, memoire.processus.size),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

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

            VueMemoire.STOCKAGE -> {
                item {
                    CarteStockage(
                        stockage = stockage,
                        chargement = etat.chargement,
                        onActualiser = onActualiserStockage,
                    )
                }

                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.storage_largest, applications.size),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.storage_estimate),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Les barres se comparent à la plus lourde : rapportées à 50 Go, toutes seraient vides.
                val reference = applications.firstOrNull()?.totalOctets ?: 1L
                items(applications, key = { it.paquet }) { application ->
                    VueApplication(
                        application = application,
                        nomConnu = etat.catalogue.entrees.firstOrNull { it.paquet == application.paquet }?.nom
                            ?: etat.catalogue.nomLauncher(application.paquet),
                        reference = reference,
                    )
                }
            }
        }
    }
}

/** Mémoire vive ou stockage : le même interrupteur que les filtres de l'onglet Paquets. */
@Composable
private fun ChoixVue(vue: VueMemoire, onVue: (VueMemoire) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
    ) {
        VueMemoire.entries.forEachIndexed { rang, choix ->
            SegmentedButton(
                selected = vue == choix,
                onClick = { onVue(choix) },
                shape = SegmentedButtonDefaults.itemShape(index = rang, count = VueMemoire.entries.size),
                // Sans coche, comme les filtres de l'onglet Paquets : la couleur suffit à désigner la vue.
                icon = {},
            ) {
                Text(
                    stringResource(
                        if (choix == VueMemoire.VIVE) R.string.memory_view_ram else R.string.memory_view_storage,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CarteStockage(stockage: RepartitionStockage, chargement: Boolean, onActualiser: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (stockage.renseignee) {
                Jauge(stockage.utiliseKo, stockage.totalKo)
                Ligne(stringResource(R.string.memory_total), taille(stockage.totalKo * 1024))
                Ligne(stringResource(R.string.memory_used), taille(stockage.utiliseKo * 1024))
                Ligne(stringResource(R.string.memory_free), taille(stockage.libreKo * 1024))

                // La répartition par nature, quand Android la donne.
                val detail = listOf(
                    R.string.storage_apps to stockage.applicationsOctets,
                    R.string.storage_app_data to stockage.donneesOctets,
                    R.string.storage_cache to stockage.cacheOctets,
                    R.string.storage_photos to stockage.photosOctets,
                    R.string.storage_videos to stockage.videosOctets,
                    R.string.storage_audio to stockage.audioOctets,
                    R.string.storage_downloads to stockage.telechargementsOctets,
                    R.string.storage_other to stockage.autresOctets,
                ).filter { it.second > 0 }
                if (detail.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.storage_breakdown),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    detail.forEach { (libelle, octets) -> Ligne(stringResource(libelle), taille(octets)) }
                }
            } else {
                Text(
                    text = stringResource(R.string.memory_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = onActualiser, enabled = !chargement) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun VueApplication(application: StockageApplication, nomConnu: String?, reference: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nomConnu ?: application.paquet,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (nomConnu != null) {
                    Text(
                        text = application.paquet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.storage_app_detail,
                        taille(application.applicationOctets),
                        taille(application.donneesOctets),
                        taille(application.cacheOctets),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = taille(application.totalOctets),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Jauge(application.totalOctets, reference)
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

/** En gigaoctets avec une décimale à partir d'un Go, en mégaoctets en dessous. */
@Composable
private fun taille(octets: Long): String =
    if (octets >= UN_GO) {
        stringResource(R.string.size_gb, "%.1f".format(Locale.getDefault(), octets / UN_GO.toDouble()))
    } else {
        stringResource(R.string.memory_mb, octets / UN_MO)
    }

private const val UN_MO = 1024L * 1024
private const val UN_GO = UN_MO * 1024
