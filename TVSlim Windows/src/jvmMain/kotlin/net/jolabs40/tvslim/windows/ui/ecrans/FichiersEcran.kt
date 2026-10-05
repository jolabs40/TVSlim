package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.fichiers.AvanceeDepot
import net.jolabs40.tvslim.fichiers.CheminDistant
import net.jolabs40.tvslim.fichiers.EntreeDistante
import net.jolabs40.tvslim.fichiers.EtatExplorateur
import net.jolabs40.tvslim.fichiers.LectureDossier
import net.jolabs40.tvslim.fichiers.NatureEntree
import net.jolabs40.tvslim.fichiers.NatureRaccourci
import net.jolabs40.tvslim.fichiers.Raccourci
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.fichiers.SensTransfert
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.action_refresh
import net.jolabs40.tvslim.windows.ressources.baseline_arrow_upward_24
import net.jolabs40.tvslim.windows.ressources.baseline_create_new_folder_24
import net.jolabs40.tvslim.windows.ressources.baseline_delete_24
import net.jolabs40.tvslim.windows.ressources.baseline_download_24
import net.jolabs40.tvslim.windows.ressources.baseline_drive_folder_upload_24
import net.jolabs40.tvslim.windows.ressources.baseline_edit_24
import net.jolabs40.tvslim.windows.ressources.baseline_folder_24
import net.jolabs40.tvslim.windows.ressources.baseline_folder_open_24
import net.jolabs40.tvslim.windows.ressources.baseline_home_24
import net.jolabs40.tvslim.windows.ressources.baseline_insert_drive_file_24
import net.jolabs40.tvslim.windows.ressources.baseline_link_24
import net.jolabs40.tvslim.windows.ressources.baseline_refresh_24
import net.jolabs40.tvslim.windows.ressources.baseline_upload_file_24
import net.jolabs40.tvslim.windows.ressources.baseline_usb_24
import net.jolabs40.tvslim.windows.ressources.files_refused_silent
import net.jolabs40.tvslim.windows.ressources.files_copy
import net.jolabs40.tvslim.windows.ressources.files_copying
import net.jolabs40.tvslim.windows.ressources.files_create
import net.jolabs40.tvslim.windows.ressources.files_delete
import net.jolabs40.tvslim.windows.ressources.files_deleting
import net.jolabs40.tvslim.windows.ressources.files_denied
import net.jolabs40.tvslim.windows.ressources.files_empty
import net.jolabs40.tvslim.windows.ressources.files_examining
import net.jolabs40.tvslim.windows.ressources.files_failed
import net.jolabs40.tvslim.windows.ressources.files_go
import net.jolabs40.tvslim.windows.ressources.files_go_to
import net.jolabs40.tvslim.windows.ressources.files_go_to_label
import net.jolabs40.tvslim.windows.ressources.files_hint
import net.jolabs40.tvslim.windows.ressources.files_last_copy
import net.jolabs40.tvslim.windows.ressources.files_last_failures
import net.jolabs40.tvslim.windows.ressources.files_last_failures_copy
import net.jolabs40.tvslim.windows.ressources.files_link
import net.jolabs40.tvslim.windows.ressources.files_loading
import net.jolabs40.tvslim.windows.ressources.files_new_folder
import net.jolabs40.tvslim.windows.ressources.files_new_folder_label
import net.jolabs40.tvslim.windows.ressources.files_not_connected
import net.jolabs40.tvslim.windows.ressources.files_not_found
import net.jolabs40.tvslim.windows.ressources.files_open_folder
import net.jolabs40.tvslim.windows.ressources.files_reading_content
import net.jolabs40.tvslim.windows.ressources.files_send_files
import net.jolabs40.tvslim.windows.ressources.files_send_folder
import net.jolabs40.tvslim.windows.ressources.files_sending
import net.jolabs40.tvslim.windows.ressources.files_sending_bytes
import net.jolabs40.tvslim.windows.ressources.files_stop
import net.jolabs40.tvslim.windows.ressources.files_up
import net.jolabs40.tvslim.windows.ressources.shortcut_downloads
import net.jolabs40.tvslim.windows.ressources.shortcut_internal
import net.jolabs40.tvslim.windows.ressources.shortcut_movies
import net.jolabs40.tvslim.windows.ressources.shortcut_music
import net.jolabs40.tvslim.windows.ressources.shortcut_pictures
import net.jolabs40.tvslim.windows.ressources.shortcut_root
import net.jolabs40.tvslim.windows.ressources.shortcut_temp
import net.jolabs40.tvslim.windows.ressources.shortcut_volume
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    /** Ouvre « Enregistrer sous » pour un fichier, le choix d'un dossier pour un dossier, puis copie. */
    val onCopier: (EntreeDistante) -> Unit,
    val onConfirmerCopie: () -> Unit,
    val onAnnulerCopie: () -> Unit,
    val onSupprimer: (EntreeDistante) -> Unit,
    val onConfirmerSuppression: () -> Unit,
    val onAnnulerSuppression: () -> Unit,
    /** Montre dans l'Explorateur le dossier où est arrivée la dernière copie. */
    val onOuvrirDossierLocal: (String) -> Unit,
)

/**
 * Les dossiers du téléviseur, comme dans l'Explorateur : des raccourcis, le fil du chemin, la liste. Ce qu'on
 * glisse dans la fenêtre, ou qu'on choisit par les deux boutons, part dans le dossier affiché.
 *
 * Un clic sur un dossier y entre. Un fichier ne s'ouvre pas : le téléviseur n'a rien pour le montrer ici. Copier
 * vers le PC et supprimer se trouvent au survol d'une ligne, et au clic droit.
 */
@Composable
fun FichiersEcran(connecte: Boolean, etat: EtatExplorateur, actions: ActionsFichiers) {
    if (!connecte) {
        EcranVide(stringResource(Res.string.files_not_connected))
        return
    }
    // Première visite sur ce téléviseur : le stockage interne se lit sans qu'on le demande.
    LaunchedEffect(Unit) { actions.onDemarrer() }

    var nouveauDossier by remember { mutableStateOf(false) }
    var allerA by remember { mutableStateOf(false) }

    etat.confirmation?.let { plan ->
        ConfirmationDepot(plan = plan, onConfirmer = actions.onConfirmer, onAnnuler = actions.onAnnulerConfirmation)
    }
    etat.rapatriement?.let { plan ->
        ConfirmationRapatriement(plan = plan, onConfirmer = actions.onConfirmerCopie, onAnnuler = actions.onAnnulerCopie)
    }
    etat.suppression?.let { plan ->
        ConfirmationSuppression(
            plan = plan,
            onConfirmer = actions.onConfirmerSuppression,
            onAnnuler = actions.onAnnulerSuppression,
        )
    }
    if (nouveauDossier) {
        DialogueSaisie(
            titre = stringResource(Res.string.files_new_folder),
            libelle = stringResource(Res.string.files_new_folder_label),
            action = stringResource(Res.string.files_create),
            onValider = { nom ->
                nouveauDossier = false
                actions.onCreerDossier(nom)
            },
            onAnnuler = { nouveauDossier = false },
        )
    }
    if (allerA) {
        DialogueSaisie(
            titre = stringResource(Res.string.files_go_to),
            libelle = stringResource(Res.string.files_go_to_label),
            action = stringResource(Res.string.files_go),
            initial = etat.chemin,
            onValider = { chemin ->
                allerA = false
                actions.onOuvrir(chemin.trim())
            },
            onAnnuler = { allerA = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Raccourcis(etat, actions.onOuvrir)
            BarreChemin(etat, actions, onAllerA = { allerA = true })
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Un dossier qui ne se lit pas ne reçoit rien : l'examen le refuserait de toute façon.
                val ouvert = etat.lecture is LectureDossier.Lue
                Button(onClick = actions.onEnvoyerFichiers, enabled = ouvert && !etat.occupe) {
                    IconeBouton(Res.drawable.baseline_upload_file_24)
                    Text(stringResource(Res.string.files_send_files))
                }
                OutlinedButton(onClick = actions.onEnvoyerDossier, enabled = ouvert && !etat.occupe) {
                    IconeBouton(Res.drawable.baseline_drive_folder_upload_24)
                    Text(stringResource(Res.string.files_send_folder))
                }
                OutlinedButton(onClick = { nouveauDossier = true }, enabled = ouvert) {
                    IconeBouton(Res.drawable.baseline_create_new_folder_24)
                    Text(stringResource(Res.string.files_new_folder))
                }
                TexteSecondaire(stringResource(Res.string.files_hint), modifier = Modifier.weight(1f), petit = true)
            }
            val avancee = etat.avancee
            val dernier = etat.dernier
            when {
                etat.examen -> Avancement(texte = stringResource(Res.string.files_examining), fraction = null)
                etat.inventaire -> Avancement(texte = stringResource(Res.string.files_reading_content), fraction = null)
                etat.effacement -> Avancement(texte = stringResource(Res.string.files_deleting), fraction = null)
                avancee != null -> Envoi(avancee, actions.onArreter)
                dernier != null -> {
                    // Rien d'arrivé, rien à montrer : le dossier n'existe peut-être même pas.
                    if (dernier.sens == SensTransfert.RECEPTION && dernier.envoyes > 0) {
                        DerniereCopie(dernier, actions.onOuvrirDossierLocal)
                    }
                    if (dernier.echecs.isNotEmpty()) Echecs(dernier)
                }
            }
        }
        HorizontalDivider()
        Liste(etat, actions)
    }
}

/** Les dossiers qu'on cherche le plus, et les volumes branchés : clé USB, carte SD. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Raccourcis(etat: EtatExplorateur, onOuvrir: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        etat.raccourcis.forEach { raccourci ->
            FilterChip(
                selected = etat.chemin == raccourci.chemin,
                onClick = { onOuvrir(raccourci.chemin) },
                label = { Text(nomRaccourci(raccourci)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(iconeRaccourci(raccourci.nature)),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun nomRaccourci(raccourci: Raccourci): String = when (raccourci.nature) {
    NatureRaccourci.INTERNE -> stringResource(Res.string.shortcut_internal)
    NatureRaccourci.TELECHARGEMENTS -> stringResource(Res.string.shortcut_downloads)
    NatureRaccourci.FILMS -> stringResource(Res.string.shortcut_movies)
    NatureRaccourci.MUSIQUE -> stringResource(Res.string.shortcut_music)
    NatureRaccourci.IMAGES -> stringResource(Res.string.shortcut_pictures)
    NatureRaccourci.VOLUME -> stringResource(Res.string.shortcut_volume, raccourci.nom)
    NatureRaccourci.TEMPORAIRE -> stringResource(Res.string.shortcut_temp)
    NatureRaccourci.RACINE -> stringResource(Res.string.shortcut_root)
}

private fun iconeRaccourci(nature: NatureRaccourci): DrawableResource = when (nature) {
    NatureRaccourci.INTERNE -> Res.drawable.baseline_home_24
    NatureRaccourci.VOLUME -> Res.drawable.baseline_usb_24
    else -> Res.drawable.baseline_folder_24
}

/** Remonter, le fil du chemin — chaque étape s'ouvre d'un clic —, puis taper un chemin ou relire. */
@Composable
private fun BarreChemin(etat: EtatExplorateur, actions: ActionsFichiers, onAllerA: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onRemonter, enabled = etat.parent != null) {
            Icon(
                painter = painterResource(Res.drawable.baseline_arrow_upward_24),
                contentDescription = stringResource(Res.string.files_up),
            )
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            etat.etapes.forEachIndexed { rang, etape ->
                if (rang > 1) TexteSecondaire("›")
                // Un bouton de texte serait trop large pour « / » : sa largeur minimale écarterait le fil.
                Text(
                    text = etape.nom,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { actions.onOuvrir(etape.chemin) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onAllerA) {
            Icon(
                painter = painterResource(Res.drawable.baseline_edit_24),
                contentDescription = stringResource(Res.string.files_go_to),
            )
        }
        IconButton(onClick = actions.onActualiser) {
            Icon(
                painter = painterResource(Res.drawable.baseline_refresh_24),
                contentDescription = stringResource(Res.string.action_refresh),
            )
        }
    }
}

@Composable
private fun Envoi(avancee: AvanceeDepot, onArreter: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Avancement(
                texte = stringResource(
                    if (avancee.sens == SensTransfert.RECEPTION) Res.string.files_copying else Res.string.files_sending,
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
            TexteSecondaire(
                stringResource(Res.string.files_sending_bytes, tailleLisible(avancee.envoye), tailleLisible(avancee.total)),
                petit = true,
            )
        }
        OutlinedButton(onClick = onArreter) { Text(stringResource(Res.string.files_stop)) }
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
        TexteSecondaire(texte, petit = true)
    }
}

/** Où est arrivée la dernière copie, et de quoi l'ouvrir : on ne cherche pas ce qu'on vient de copier. */
@Composable
private fun DerniereCopie(resultat: ResultatDepot, onOuvrirDossierLocal: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TexteSecondaire(
            stringResource(Res.string.files_last_copy, resultat.envoyes, resultat.destination),
            modifier = Modifier.weight(1f, fill = false),
            petit = true,
        )
        TextButton(onClick = { onOuvrirDossierLocal(resultat.destination) }) {
            IconeBouton(Res.drawable.baseline_folder_open_24)
            Text(stringResource(Res.string.files_open_folder))
        }
    }
}

/** Ce que le dernier envoi n'a pas déposé, et pourquoi : la bannière passe, la liste reste jusqu'au suivant. */
@Composable
private fun Echecs(resultat: ResultatDepot) {
    // Un refus sans un mot du téléviseur se dit dans la langue de l'écran.
    val refuse = stringResource(Res.string.files_refused_silent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    if (resultat.sens == SensTransfert.RECEPTION) {
                        Res.string.files_last_failures_copy
                    } else {
                        Res.string.files_last_failures
                    },
                ),
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
private fun Liste(etat: EtatExplorateur, actions: ActionsFichiers) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val lecture = etat.lecture) {
            null -> Attente()
            is LectureDossier.Introuvable -> Constat(stringResource(Res.string.files_not_found))
            is LectureDossier.Refusee -> Constat(stringResource(Res.string.files_denied))
            is LectureDossier.Echouee -> Constat(stringResource(Res.string.files_failed, lecture.motif))
            is LectureDossier.Lue -> if (lecture.entrees.isEmpty()) {
                Constat(stringResource(Res.string.files_empty))
            } else {
                val format = remember {
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
                }
                val defilement = rememberLazyListState()
                LazyColumn(state = defilement, modifier = Modifier.fillMaxSize()) {
                    items(lecture.entrees, key = { it.nom }) { entree ->
                        LigneEntree(
                            entree = entree,
                            date = format.format(Instant.ofEpochMilli(entree.date).atZone(ZoneId.systemDefault())),
                            // Une opération à la fois : pendant un envoi, une copie ou une suppression, rien ne s'offre.
                            actif = !etat.occupe,
                            onOuvrir = { actions.onOuvrir(CheminDistant.joindre(lecture.chemin, entree.nom)) },
                            onCopier = { actions.onCopier(entree) },
                            onSupprimer = { actions.onSupprimer(entree) },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(defilement),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
        // Une relecture du même dossier garde la liste à l'écran : un trait suffit à dire qu'elle se fait.
        if (etat.chargement && etat.lecture != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/**
 * Une entrée du dossier. Copier vers le PC et supprimer apparaissent au survol — cent lignes n'ont pas à porter
 * deux cents boutons — et au clic droit, comme dans l'Explorateur.
 */
@Composable
private fun LigneEntree(
    entree: EntreeDistante,
    date: String,
    actif: Boolean,
    onOuvrir: () -> Unit,
    onCopier: () -> Unit,
    onSupprimer: () -> Unit,
) {
    // Un nom qui commence par un point est caché sur Android aussi : il reste là, en retrait.
    val couleur = if (entree.nom.startsWith('.')) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // Un tube ou un périphérique ne se copie pas : sa lecture ne finirait jamais.
    val copiable = entree.nature != NatureEntree.AUTRE
    val libelleCopier = stringResource(Res.string.files_copy)
    val libelleSupprimer = stringResource(Res.string.files_delete)
    val interactions = remember { MutableInteractionSource() }
    val survolee by interactions.collectIsHoveredAsState()

    ContextMenuArea(
        items = {
            listOfNotNull(
                ContextMenuItem(libelleCopier, onCopier).takeIf { copiable },
                ContextMenuItem(libelleSupprimer, onSupprimer),
            )
        },
        enabled = actif,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactions)
                // Un fichier ne s'ouvre pas, mais sa ligne s'éclaire au survol comme celle d'un dossier : on voit à
                // quelle ligne appartiennent les boutons.
                .then(if (entree.dossier) Modifier.clickable(onClick = onOuvrir) else Modifier.indication(interactions, ripple()))
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContenuLigne(entree, date, couleur)
            // La place reste réservée : la ligne ne bouge pas quand les boutons paraissent.
            Row(
                modifier = Modifier.width(72.dp).height(32.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (survolee && actif) {
                    if (copiable) ActionLigne(Res.drawable.baseline_download_24, libelleCopier, onCopier)
                    ActionLigne(
                        Res.drawable.baseline_delete_24,
                        libelleSupprimer,
                        onSupprimer,
                        teinte = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Un bouton d'icône de 32 dp, son nom en infobulle : une icône seule ne dit pas toujours ce qu'elle fait. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionLigne(
    icone: DrawableResource,
    libelle: String,
    onClick: () -> Unit,
    teinte: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = libelle,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                painter = painterResource(icone),
                contentDescription = libelle,
                modifier = Modifier.size(20.dp),
                tint = teinte,
            )
        }
    }
}

/** Icône, nom, lien, taille et date : la ligne telle qu'elle se lit. */
@Composable
private fun RowScope.ContenuLigne(entree: EntreeDistante, date: String, couleur: Color) {
    Icon(
        painter = painterResource(
            if (entree.dossier) Res.drawable.baseline_folder_24 else Res.drawable.baseline_insert_drive_file_24,
        ),
        contentDescription = null,
        tint = if (entree.dossier) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = entree.nom,
        modifier = Modifier.weight(1f),
        color = couleur,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (entree.lien) {
        Icon(
            painter = painterResource(Res.drawable.baseline_link_24),
            contentDescription = stringResource(Res.string.files_link),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = if (entree.dossier) "" else tailleLisible(entree.taille),
        modifier = Modifier.width(90.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
    Text(
        text = date,
        modifier = Modifier.width(150.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        TexteSecondaire(stringResource(Res.string.files_loading))
    }
}

@Composable
private fun Constat(texte: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        TexteSecondaire(texte)
    }
}

@Composable
private fun IconeBouton(icone: DrawableResource) {
    Icon(painter = painterResource(icone), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** Au-delà, la carte deviendrait la page : la liste complète n'apprendrait rien de plus. */
private const val ECHECS_MAX = 20
