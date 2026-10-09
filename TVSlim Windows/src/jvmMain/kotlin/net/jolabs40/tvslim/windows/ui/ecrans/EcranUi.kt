package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.ecran.ScrcpyEpingle
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.about_close
import net.jolabs40.tvslim.windows.ressources.baseline_photo_camera_24
import net.jolabs40.tvslim.windows.ressources.baseline_screen_share_24
import net.jolabs40.tvslim.windows.ressources.baseline_stop_circle_24
import net.jolabs40.tvslim.windows.ressources.baseline_videocam_24
import net.jolabs40.tvslim.windows.ressources.capture_copy
import net.jolabs40.tvslim.windows.ressources.capture_drm
import net.jolabs40.tvslim.windows.ressources.capture_open_folder
import net.jolabs40.tvslim.windows.ressources.capture_saved
import net.jolabs40.tvslim.windows.ressources.capture_title
import net.jolabs40.tvslim.windows.ressources.closing_text
import net.jolabs40.tvslim.windows.ressources.closing_title
import net.jolabs40.tvslim.windows.ressources.confirm_cancel
import net.jolabs40.tvslim.windows.ressources.record_finishing
import net.jolabs40.tvslim.windows.ressources.record_starting
import net.jolabs40.tvslim.windows.ressources.screen_capture
import net.jolabs40.tvslim.windows.ressources.screen_mirror
import net.jolabs40.tvslim.windows.ressources.screen_mirror_stop
import net.jolabs40.tvslim.windows.ressources.screen_record
import net.jolabs40.tvslim.windows.ressources.screen_record_stop
import net.jolabs40.tvslim.windows.ressources.scrcpy_authorization
import net.jolabs40.tvslim.windows.ressources.scrcpy_download
import net.jolabs40.tvslim.windows.ressources.scrcpy_downloading
import net.jolabs40.tvslim.windows.ressources.scrcpy_text
import net.jolabs40.tvslim.windows.ressources.scrcpy_title
import net.jolabs40.tvslim.windows.ressources.video_saved
import net.jolabs40.tvslim.windows.ressources.video_no_sound
import net.jolabs40.tvslim.windows.ressources.video_title
import net.jolabs40.tvslim.windows.ui.CaptureFaite
import net.jolabs40.tvslim.windows.ui.EtatEcran
import net.jolabs40.tvslim.windows.ui.PhaseEnregistrement
import net.jolabs40.tvslim.windows.ui.PhaseScrcpy
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image as ImageSkia
import java.io.File

/**
 * Screenshot, mirror and record buttons in the top bar, visible from every tab. While mirroring or recording, the
 * matching button turns into its stop button. Recording closes the mirror; the mirror waits for a recording to end.
 */
@Composable
fun ActionsEcran(
    etat: EtatEcran,
    connecte: Boolean,
    onCapturer: () -> Unit,
    onMiroir: () -> Unit,
    onArreterMiroir: () -> Unit,
    onEnregistrer: () -> Unit,
    onArreterEnregistrement: () -> Unit,
) {
    val phase = etat.scrcpy
    val video = etat.enregistrement
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (etat.captureEnCours) {
            Attente(libelle = stringResource(Res.string.screen_capture))
        } else {
            BoutonEcran(Res.drawable.baseline_photo_camera_24, stringResource(Res.string.screen_capture), connecte, onCapturer)
        }

        when (phase) {
            is PhaseScrcpy.Telechargement -> Attente(stringResource(Res.string.scrcpy_downloading), phase.progression)
            PhaseScrcpy.Actif -> BoutonEcran(
                icone = Res.drawable.baseline_screen_share_24,
                libelle = stringResource(Res.string.screen_mirror_stop),
                actif = true,
                onClick = onArreterMiroir,
                teinte = MaterialTheme.colorScheme.primary,
            )

            PhaseScrcpy.Inactif -> BoutonEcran(
                icone = Res.drawable.baseline_screen_share_24,
                libelle = stringResource(Res.string.screen_mirror),
                actif = connecte && video == PhaseEnregistrement.Inactif,
                onClick = onMiroir,
            )
        }

        when (video) {
            PhaseEnregistrement.Inactif -> BoutonEcran(
                icone = Res.drawable.baseline_videocam_24,
                libelle = stringResource(Res.string.screen_record),
                actif = connecte && phase !is PhaseScrcpy.Telechargement,
                onClick = onEnregistrer,
            )

            PhaseEnregistrement.Demarrage -> Attente(stringResource(Res.string.record_starting))
            is PhaseEnregistrement.EnCours -> {
                BoutonEcran(
                    icone = Res.drawable.baseline_stop_circle_24,
                    libelle = stringResource(Res.string.screen_record_stop),
                    actif = true,
                    onClick = onArreterEnregistrement,
                    teinte = MaterialTheme.colorScheme.error,
                )
                Chrono(video)
            }

            PhaseEnregistrement.Arret -> Attente(stringResource(Res.string.record_finishing))
            is PhaseEnregistrement.Copie -> Attente(stringResource(Res.string.record_finishing), video.progression)
        }
    }
}

/** Elapsed recording time, plus the limit before Android 14. */
@Composable
private fun Chrono(enCours: PhaseEnregistrement.EnCours) {
    var maintenant by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enCours.debut) {
        while (true) {
            maintenant = System.currentTimeMillis()
            delay(500)
        }
    }
    val ecoule = duree(((maintenant - enCours.debut) / 1000).coerceAtLeast(0))
    Text(
        text = enCours.limiteS?.let { "$ecoule / ${duree(it.toLong())}" } ?: ecoule,
        modifier = Modifier.padding(end = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun duree(secondes: Long): String = "%d:%02d".format(secondes / 60, secondes % 60)

/** A spinner in place of a button during an operation, filled according to [progression]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Attente(libelle: String, progression: Float? = null) {
    TooltipArea(tooltip = { Infobulle(libelle) }) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (progression == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { progression }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Infobulle(libelle: String) {
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
}

/** Icon button with its name as a tooltip, since an icon alone is not always clear. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoutonEcran(
    icone: DrawableResource,
    libelle: String,
    actif: Boolean,
    onClick: () -> Unit,
    teinte: Color = Color.Unspecified,
) {
    TooltipArea(tooltip = { Infobulle(libelle) }) {
        IconButton(onClick = onClick, enabled = actif) {
            if (teinte == Color.Unspecified) {
                Icon(painterResource(icone), contentDescription = libelle)
            } else {
                Icon(painterResource(icone), contentDescription = libelle, tint = teinte)
            }
        }
    }
}

/** Preview of a screenshot just saved. */
@Composable
fun ApercuCaptureDialogue(
    capture: CaptureFaite,
    onCopier: () -> Unit,
    onOuvrirDossier: () -> Unit,
    onFermer: () -> Unit,
) {
    val image = remember(capture) { ImageSkia.makeFromEncoded(capture.png).toComposeImageBitmap() }
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(Res.string.capture_title)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = capture.fichier.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 405.dp),
                    contentScale = ContentScale.Fit,
                )
                SelectionContainer {
                    Column {
                        Text(
                            text = stringResource(Res.string.capture_saved, capture.fichier.parent.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TexteSecondaire("${capture.fichier.name} — ${capture.largeur} × ${capture.hauteur}", petit = true)
                    }
                }
                TexteSecondaire(stringResource(Res.string.capture_drm), petit = true)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOuvrirDossier) { Text(stringResource(Res.string.capture_open_folder)) }
                TextButton(onClick = onCopier) { Text(stringResource(Res.string.capture_copy)) }
                Button(onClick = onFermer) { Text(stringResource(Res.string.about_close)) }
            }
        },
    )
}

/**
 * scrcpy is missing: what TV Slim will download, from where, and the second authorization the TV will ask for.
 * The same dialog then shows download progress.
 */
@Composable
fun TelechargementScrcpyDialogue(
    phase: PhaseScrcpy,
    dossier: File,
    onTelecharger: () -> Unit,
    onAnnuler: () -> Unit,
) {
    val enCours = phase is PhaseScrcpy.Telechargement
    AlertDialog(
        onDismissRequest = { if (!enCours) onAnnuler() },
        title = { Text(stringResource(Res.string.scrcpy_title)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.scrcpy_text, ScrcpyEpingle.VERSION, dossier.path))
                Text(stringResource(Res.string.scrcpy_authorization))
                if (phase is PhaseScrcpy.Telechargement) {
                    Text(stringResource(Res.string.scrcpy_downloading), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { phase.progression }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = onTelecharger, enabled = !enCours) { Text(stringResource(Res.string.scrcpy_download)) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler, enabled = !enCours) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/** Where the video of a finished recording was saved. */
@Composable
fun VideoEnregistreeDialogue(video: File, onOuvrirDossier: () -> Unit, onFermer: () -> Unit) {
    AlertDialog(
        onDismissRequest = onFermer,
        icon = { Icon(painterResource(Res.drawable.baseline_videocam_24), contentDescription = null) },
        title = { Text(stringResource(Res.string.video_title)) },
        text = {
            // Wide enough for a folder path to fit on one line.
            Box(modifier = Modifier.width(460.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.video_saved, video.parent.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                        TexteSecondaire(video.name, petit = true)
                        TexteSecondaire(stringResource(Res.string.video_no_sound), petit = true)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOuvrirDossier) { Text(stringResource(Res.string.capture_open_folder)) }
                Button(onClick = onFermer) { Text(stringResource(Res.string.about_close)) }
            }
        },
    )
}

/** Shown when TV Slim is closed during a recording: the video is copied first. */
@Composable
fun FermetureDialogue(phase: PhaseEnregistrement) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.closing_title)) },
        text = {
            Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.closing_text))
                if (phase is PhaseEnregistrement.Copie) {
                    LinearProgressIndicator(progress = { phase.progression }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}
