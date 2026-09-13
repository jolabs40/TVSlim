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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.ProcessusMemoire
import net.jolabs40.tvslim.device.RepartitionMemoire
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.device.StockageApplication
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
import net.jolabs40.tvslim.windows.ressources.memory_view_ram
import net.jolabs40.tvslim.windows.ressources.memory_view_storage
import net.jolabs40.tvslim.windows.ressources.memory_zram
import net.jolabs40.tvslim.windows.ressources.size_gb
import net.jolabs40.tvslim.windows.ressources.storage_app_data
import net.jolabs40.tvslim.windows.ressources.storage_app_detail
import net.jolabs40.tvslim.windows.ressources.storage_apps
import net.jolabs40.tvslim.windows.ressources.storage_audio
import net.jolabs40.tvslim.windows.ressources.storage_breakdown
import net.jolabs40.tvslim.windows.ressources.storage_cache
import net.jolabs40.tvslim.windows.ressources.storage_downloads
import net.jolabs40.tvslim.windows.ressources.storage_estimate
import net.jolabs40.tvslim.windows.ressources.storage_largest
import net.jolabs40.tvslim.windows.ressources.storage_other
import net.jolabs40.tvslim.windows.ressources.storage_photos
import net.jolabs40.tvslim.windows.ressources.storage_title
import net.jolabs40.tvslim.windows.ressources.storage_videos
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.Jauge
import net.jolabs40.tvslim.windows.ui.composants.LigneValeur
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/** Ce que l'onglet montre : la mémoire vive, ou le stockage interne. */
private enum class VueMemoire { VIVE, STOCKAGE }

/**
 * Mémoire vive et stockage, d'un interrupteur.
 *
 * Le pendant du débloat : la liste des paquets dit ce qui est installé, celle-ci ce qui coûte
 * réellement — en mémoire vive ce qui tourne, en stockage ce qui occupe la place. Sur un bureau,
 * chaque liste a sa propre colonne au lieu de défiler sous les cartes.
 */
@Composable
fun MemoireEcran(
    etat: EtatApp,
    onActualiser: () -> Unit,
    onActualiserStockage: () -> Unit,
    onForcerArret: (String) -> Unit,
    onRedefinirReference: () -> Unit,
) {
    if (!etat.connecte) {
        EcranVide(stringResource(Res.string.memory_not_connected))
        return
    }

    var vue by remember { mutableStateOf(VueMemoire.VIVE) }

    // Première visite sur ce téléviseur : on lit sans attendre qu'on le demande.
    LaunchedEffect(etat.connexion.hote) {
        if (!etat.memoire.renseignee) onActualiser()
    }
    LaunchedEffect(etat.connexion.hote, vue) {
        if (vue == VueMemoire.STOCKAGE && !etat.stockage.renseignee) onActualiserStockage()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChoixVue(
            vue = vue,
            onVue = { vue = it },
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        when (vue) {
            VueMemoire.VIVE -> MemoireVive(etat, onActualiser, onForcerArret, onRedefinirReference)
            VueMemoire.STOCKAGE -> Stockage(etat, onActualiserStockage)
        }
    }
}

/** Mémoire vive ou stockage : le même interrupteur que les filtres de l'onglet Paquets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoixVue(vue: VueMemoire, onVue: (VueMemoire) -> Unit, modifier: Modifier = Modifier) {
    // Largeur fixe et sans coche, comme les filtres : sinon « Mémoire vive » passe sur deux lignes.
    SingleChoiceSegmentedButtonRow(modifier = modifier.width(360.dp)) {
        VueMemoire.entries.forEachIndexed { rang, choix ->
            SegmentedButton(
                selected = vue == choix,
                onClick = { onVue(choix) },
                shape = SegmentedButtonDefaults.itemShape(index = rang, count = VueMemoire.entries.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        if (choix == VueMemoire.VIVE) Res.string.memory_view_ram else Res.string.memory_view_storage,
                    ),
                )
            }
        }
    }
}

// --- Mémoire vive ---------------------------------------------------------------------------

@Composable
private fun MemoireVive(
    etat: EtatApp,
    onActualiser: () -> Unit,
    onForcerArret: (String) -> Unit,
    onRedefinirReference: () -> Unit,
) {
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

// --- Stockage -------------------------------------------------------------------------------

@Composable
private fun Stockage(etat: EtatApp, onActualiser: () -> Unit) {
    val stockage = etat.stockage
    // Les centaines de paquets système de quelques Ko n'apprennent rien : on liste à partir d'un Mo.
    val applications = stockage.applications.filter { it.totalOctets >= UN_MO }
    // Les barres se comparent à la plus lourde : rapportées à 50 Go, toutes seraient vides.
    val reference = applications.firstOrNull()?.totalOctets ?: 1L

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CarteStockage(
                stockage = stockage,
                chargement = etat.chargement,
                tentee = etat.lectureStockageTentee,
                onActualiser = onActualiser,
            )
        }

        VerticalDivider()

        Column(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
            Text(
                text = stringResource(Res.string.storage_largest, applications.size),
                modifier = Modifier.padding(start = 20.dp, top = 20.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TexteSecondaire(
                stringResource(Res.string.storage_estimate),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                petit = true,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val liste = rememberLazyListState()
                LazyColumn(state = liste, modifier = Modifier.fillMaxSize()) {
                    items(applications, key = { it.paquet }) { application ->
                        VueApplication(
                            application = application,
                            nomConnu = etat.catalogue.entrees.firstOrNull { it.paquet == application.paquet }?.nom
                                ?: etat.catalogue.nomLauncher(application.paquet),
                            reference = reference,
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
private fun CarteStockage(
    stockage: RepartitionStockage,
    chargement: Boolean,
    tentee: Boolean,
    onActualiser: () -> Unit,
) {
    CarteSection(titre = stringResource(Res.string.storage_title), espacement = 6.dp) {
        when {
            stockage.renseignee -> {
                Jauge(stockage.utiliseKo, stockage.totalKo)
                LigneValeur(stringResource(Res.string.memory_total), taille(stockage.totalKo * 1024))
                LigneValeur(stringResource(Res.string.memory_used), taille(stockage.utiliseKo * 1024))
                LigneValeur(stringResource(Res.string.memory_free), taille(stockage.libreKo * 1024))

                // La répartition par nature, quand Android la donne.
                val detail = listOf(
                    Res.string.storage_apps to stockage.applicationsOctets,
                    Res.string.storage_app_data to stockage.donneesOctets,
                    Res.string.storage_cache to stockage.cacheOctets,
                    Res.string.storage_photos to stockage.photosOctets,
                    Res.string.storage_videos to stockage.videosOctets,
                    Res.string.storage_audio to stockage.audioOctets,
                    Res.string.storage_downloads to stockage.telechargementsOctets,
                    Res.string.storage_other to stockage.autresOctets,
                ).filter { it.second > 0 }
                if (detail.isNotEmpty()) {
                    TexteSecondaire(
                        stringResource(Res.string.storage_breakdown),
                        modifier = Modifier.padding(top = 8.dp),
                        petit = true,
                    )
                    detail.forEach { (libelle, octets) -> LigneValeur(stringResource(libelle), taille(octets)) }
                }
            }

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
private fun VueApplication(application: StockageApplication, nomConnu: String?, reference: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nomConnu ?: application.paquet,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (nomConnu != null) TexteSecondaire(application.paquet, petit = true)
                TexteSecondaire(
                    stringResource(
                        Res.string.storage_app_detail,
                        taille(application.applicationOctets),
                        taille(application.donneesOctets),
                        taille(application.cacheOctets),
                    ),
                    petit = true,
                )
            }
            Text(
                text = taille(application.totalOctets),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Jauge(application.totalOctets, reference, modifier = Modifier.padding(top = 4.dp))
    }
}

/** En gigaoctets avec une décimale à partir d'un Go, en mégaoctets en dessous. */
@Composable
private fun taille(octets: Long): String =
    if (octets >= UN_GO) {
        stringResource(Res.string.size_gb, "%.1f".format(Locale.getDefault(), octets / UN_GO.toDouble()))
    } else {
        stringResource(Res.string.memory_mb, octets / UN_MO)
    }

private const val UN_MO = 1024L * 1024
private const val UN_GO = UN_MO * 1024
