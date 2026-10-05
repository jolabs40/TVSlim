package net.jolabs40.tvslim.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.fichiers.AvanceeDepot
import net.jolabs40.tvslim.fichiers.CheminDistant
import net.jolabs40.tvslim.fichiers.DOSSIER_DE_DEPART
import net.jolabs40.tvslim.fichiers.EntreeDistante
import net.jolabs40.tvslim.fichiers.EtatExplorateur
import net.jolabs40.tvslim.fichiers.LectureDossier
import net.jolabs40.tvslim.fichiers.NatureRaccourci
import net.jolabs40.tvslim.fichiers.Raccourci
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EnvoiEnAttente
import java.text.DateFormat
import java.util.Date

/** Ce que l'onglet Fichiers demande au pilote. */
data class ActionsFichiers(
    val onDemarrer: () -> Unit,
    val onOuvrir: (String) -> Unit,
    val onRemonter: () -> Unit,
    val onActualiser: () -> Unit,
    val onEnvoyerFichiers: () -> Unit,
    val onEnvoyerDossier: () -> Unit,
    val onCreerDossier: (String) -> Unit,
    val onConfirmer: () -> Unit,
    val onAnnulerConfirmation: () -> Unit,
    val onArreter: () -> Unit,
    /** Ce qui attend part dans le dossier affiché : examen, puis confirmation. */
    val onEnvoyerIci: () -> Unit,
    val onAbandonnerEnvoi: () -> Unit,
)

/**
 * Les dossiers du téléviseur : des raccourcis, le fil du chemin, la liste, et « Envoyer au téléviseur » pour y
 * déposer des documents ou un dossier du téléphone. Un appui sur un dossier y entre ; Retour remonte, jusqu'au
 * stockage interne.
 *
 * On choisit d'abord quoi envoyer, puis où : tant que [enAttente] n'est pas parti, un bandeau dit ce qui attend
 * et qu'il faut ouvrir le dossier de destination, et la barre du bas l'y envoie en le nommant. Retour, au
 * stockage interne, y renonce.
 */
@Composable
fun FichiersScreen(connecte: Boolean, etat: EtatExplorateur, enAttente: EnvoiEnAttente?, actions: ActionsFichiers) {
    if (!connecte) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.files_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Première visite sur ce téléviseur : le stockage interne se lit sans qu'on le demande.
    LaunchedEffect(Unit) { actions.onDemarrer() }
    // Déclaré d'abord, il ne sert qu'une fois qu'on ne peut plus remonter.
    BackHandler(enabled = enAttente != null) { actions.onAbandonnerEnvoi() }
    BackHandler(enabled = etat.parent != null && etat.chemin != DOSSIER_DE_DEPART) { actions.onRemonter() }

    var nouveauDossier by rememberSaveable { mutableStateOf(false) }
    var allerA by rememberSaveable { mutableStateOf(false) }
    val ouvert = etat.lecture is LectureDossier.Lue

    etat.confirmation?.let { plan ->
        ConfirmationDepot(plan = plan, onConfirmer = actions.onConfirmer, onAnnuler = actions.onAnnulerConfirmation)
    }
    if (nouveauDossier) {
        DialogueSaisie(
            titre = stringResource(R.string.files_new_folder),
            libelle = stringResource(R.string.files_new_folder_label),
            action = stringResource(R.string.files_create),
            onValider = { nom ->
                nouveauDossier = false
                actions.onCreerDossier(nom)
            },
            onAnnuler = { nouveauDossier = false },
        )
    }
    if (allerA) {
        DialogueSaisie(
            titre = stringResource(R.string.files_go_to),
            libelle = stringResource(R.string.files_go_to_label),
            action = stringResource(R.string.files_go),
            initial = etat.chemin,
            onValider = { chemin ->
                allerA = false
                actions.onOuvrir(chemin.trim())
            },
            onAnnuler = { allerA = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            enAttente?.let { BandeauEnAttente(it) }
            Raccourcis(etat, actions.onOuvrir)
            BarreChemin(
                etat = etat,
                actions = actions,
                ouvert = ouvert,
                onNouveauDossier = { nouveauDossier = true },
                onAllerA = { allerA = true },
            )
            val avancee = etat.avancee
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                when {
                    etat.examen -> Avancement(texte = stringResource(R.string.files_examining), fraction = null)
                    avancee != null -> Envoi(avancee, actions.onArreter)
                    else -> etat.dernier?.takeIf { it.echecs.isNotEmpty() }?.let { Echecs(it) }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            Liste(etat, actions.onOuvrir)
        }
        when {
            enAttente != null -> BarreDestination(
                // La racine n'a pas de nom : elle se dit « / ».
                dossier = CheminDistant.nom(etat.chemin).ifEmpty { CheminDistant.RACINE },
                actif = ouvert && !etat.occupe,
                onAnnuler = actions.onAbandonnerEnvoi,
                onEnvoyer = actions.onEnvoyerIci,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            ouvert && !etat.occupe -> EnvoyerAuTeleviseur(
                onFichiers = actions.onEnvoyerFichiers,
                onDossier = actions.onEnvoyerDossier,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

/** Ce qui attend, et ce qu'il reste à faire : ouvrir le dossier du téléviseur qui le recevra. */
@Composable
private fun BandeauEnAttente(attente: EnvoiEnAttente) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (attente.dossier) Icons.Filled.DriveFolderUpload else Icons.Filled.UploadFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        attente.dossier -> stringResource(R.string.files_pending_folder, attente.nom)
                        attente.nombre == 1 -> stringResource(R.string.files_pending_file, attente.nom)
                        else -> pluralStringResource(R.plurals.files_pending_count, attente.nombre, attente.nombre)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.files_pending_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** Renoncer, ou envoyer dans le dossier affiché — qu'elle nomme, pour qu'on sache où ça part. */
@Composable
private fun BarreDestination(
    dossier: String,
    actif: Boolean,
    onAnnuler: () -> Unit,
    onEnvoyer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onAnnuler) { Text(stringResource(R.string.confirm_cancel)) }
            Button(onClick = onEnvoyer, enabled = actif, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.files_send_into, dossier),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Les dossiers qu'on cherche le plus, et les volumes branchés — clé USB, carte SD —, en une ligne qui défile. */
@Composable
private fun Raccourcis(etat: EtatExplorateur, onOuvrir: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(etat.raccourcis, key = { it.chemin }) { raccourci ->
            FilterChip(
                selected = etat.chemin == raccourci.chemin,
                onClick = { onOuvrir(raccourci.chemin) },
                label = { Text(nomRaccourci(raccourci)) },
                leadingIcon = {
                    Icon(iconeRaccourci(raccourci.nature), contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }
    }
}

@Composable
private fun nomRaccourci(raccourci: Raccourci): String = when (raccourci.nature) {
    NatureRaccourci.INTERNE -> stringResource(R.string.shortcut_internal)
    NatureRaccourci.TELECHARGEMENTS -> stringResource(R.string.shortcut_downloads)
    NatureRaccourci.FILMS -> stringResource(R.string.shortcut_movies)
    NatureRaccourci.MUSIQUE -> stringResource(R.string.shortcut_music)
    NatureRaccourci.IMAGES -> stringResource(R.string.shortcut_pictures)
    NatureRaccourci.VOLUME -> stringResource(R.string.shortcut_volume, raccourci.nom)
    NatureRaccourci.TEMPORAIRE -> stringResource(R.string.shortcut_temp)
    NatureRaccourci.RACINE -> stringResource(R.string.shortcut_root)
}

private fun iconeRaccourci(nature: NatureRaccourci): ImageVector = when (nature) {
    NatureRaccourci.INTERNE -> Icons.Filled.Home
    NatureRaccourci.VOLUME -> Icons.Filled.Usb
    else -> Icons.Filled.Folder
}

/** Remonter, le fil du chemin — chaque étape s'ouvre d'un appui —, puis créer, taper un chemin, relire. */
@Composable
private fun BarreChemin(
    etat: EtatExplorateur,
    actions: ActionsFichiers,
    ouvert: Boolean,
    onNouveauDossier: () -> Unit,
    onAllerA: () -> Unit,
) {
    val defilement = rememberScrollState()
    // Le fil se lit par la fin : c'est là qu'on est.
    LaunchedEffect(etat.chemin) { defilement.scrollTo(defilement.maxValue) }
    Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onRemonter, enabled = etat.parent != null) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.files_up))
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(defilement),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            etat.etapes.forEachIndexed { rang, etape ->
                if (rang > 1) {
                    Text(text = "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = etape.nom,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { actions.onOuvrir(etape.chemin) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onNouveauDossier, enabled = ouvert) {
            Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
        }
        IconButton(onClick = onAllerA) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.files_go_to))
        }
        IconButton(onClick = actions.onActualiser) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
        }
    }
}

/** Un bouton, deux choix : des documents un à un, ou un dossier entier. La destination se choisit ensuite. */
@Composable
private fun EnvoyerAuTeleviseur(onFichiers: () -> Unit, onDossier: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ExtendedFloatingActionButton(
            onClick = { menu = true },
            icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
            text = { Text(stringResource(R.string.files_send_to_tv)) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_send_files)) },
                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                onClick = {
                    menu = false
                    onFichiers()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_send_folder)) },
                leadingIcon = { Icon(Icons.Filled.DriveFolderUpload, contentDescription = null) },
                onClick = {
                    menu = false
                    onDossier()
                },
            )
        }
    }
}

@Composable
private fun Envoi(avancee: AvanceeDepot, onArreter: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Avancement(
                texte = stringResource(
                    R.string.files_sending,
                    avancee.fichier.ifBlank { "…" },
                    avancee.rang.coerceAtLeast(1),
                    avancee.nombre,
                ),
                // Une taille inconnue ne fait pas avancer la barre : on compte alors les fichiers.
                fraction = if (avancee.total > 0) {
                    avancee.envoye.toFloat() / avancee.total
                } else {
                    (avancee.rang - 1).coerceAtLeast(0).toFloat() / avancee.nombre.coerceAtLeast(1)
                },
            )
            Text(
                text = stringResource(
                    R.string.files_sending_bytes,
                    tailleLisible(avancee.envoye),
                    tailleLisible(avancee.total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onArreter) { Text(stringResource(R.string.files_stop)) }
    }
}

@Composable
private fun Avancement(texte: String, fraction: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = texte,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Ce que le dernier envoi n'a pas déposé, et pourquoi : la bannière passe, la carte reste jusqu'au suivant. */
@Composable
private fun Echecs(resultat: ResultatDepot) {
    // Un refus sans un mot du téléviseur se dit dans la langue de l'écran.
    val refuse = stringResource(R.string.files_refused_silent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.files_last_failures),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            SelectionContainer {
                Text(
                    text = resultat.echecs.take(ECHECS_MAX).joinToString("\n") { "${it.chemin} : ${it.motif.ifBlank { refuse }}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun Liste(etat: EtatExplorateur, onOuvrir: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val lecture = etat.lecture) {
            null -> Attente()
            is LectureDossier.Introuvable -> Constat(stringResource(R.string.files_not_found))
            is LectureDossier.Refusee -> Constat(stringResource(R.string.files_denied))
            is LectureDossier.Echouee -> Constat(stringResource(R.string.files_failed, lecture.motif))
            is LectureDossier.Lue -> if (lecture.entrees.isEmpty()) {
                Constat(stringResource(R.string.files_empty))
            } else {
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                // De la place en bas pour le bouton flottant, ou la barre de destination : ni l'un ni l'autre ne
                // doit cacher la dernière ligne.
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(lecture.entrees, key = { it.nom }) { entree ->
                        LigneEntree(
                            entree = entree,
                            date = format.format(Date(entree.date)),
                            onOuvrir = { onOuvrir(CheminDistant.joindre(lecture.chemin, entree.nom)) },
                        )
                    }
                }
            }
        }
        // Une relecture du même dossier garde la liste à l'écran : un trait suffit à dire qu'elle se fait.
        if (etat.chargement && etat.lecture != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun LigneEntree(entree: EntreeDistante, date: String, onOuvrir: () -> Unit) {
    // Un nom qui commence par un point est caché sur Android aussi : il reste là, en retrait.
    val couleur = if (entree.nom.startsWith('.')) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entree.dossier) Modifier.clickable(onClick = onOuvrir) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entree.dossier) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entree.dossier) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entree.nom,
                style = MaterialTheme.typography.bodyLarge,
                color = couleur,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entree.dossier) date else "${tailleLisible(entree.taille)} · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entree.lien) {
            Icon(
                Icons.Filled.Link,
                contentDescription = stringResource(R.string.files_link),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Attente() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.files_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Constat(texte: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = texte, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Au-delà, la carte deviendrait la page : la liste complète n'apprendrait rien de plus. */
private const val ECHECS_MAX = 20
