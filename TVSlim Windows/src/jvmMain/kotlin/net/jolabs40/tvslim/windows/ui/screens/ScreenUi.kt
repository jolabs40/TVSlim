package net.jolabs40.tvslim.windows.ui.screens

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
import net.jolabs40.tvslim.windows.screen.PinnedScrcpy
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.about_close
import net.jolabs40.tvslim.windows.resources.baseline_photo_camera_24
import net.jolabs40.tvslim.windows.resources.baseline_screen_share_24
import net.jolabs40.tvslim.windows.resources.baseline_stop_circle_24
import net.jolabs40.tvslim.windows.resources.baseline_videocam_24
import net.jolabs40.tvslim.windows.resources.capture_copy
import net.jolabs40.tvslim.windows.resources.capture_drm
import net.jolabs40.tvslim.windows.resources.capture_open_folder
import net.jolabs40.tvslim.windows.resources.capture_saved
import net.jolabs40.tvslim.windows.resources.capture_title
import net.jolabs40.tvslim.windows.resources.closing_text
import net.jolabs40.tvslim.windows.resources.closing_title
import net.jolabs40.tvslim.windows.resources.confirm_cancel
import net.jolabs40.tvslim.windows.resources.record_finishing
import net.jolabs40.tvslim.windows.resources.record_starting
import net.jolabs40.tvslim.windows.resources.screen_capture
import net.jolabs40.tvslim.windows.resources.screen_mirror
import net.jolabs40.tvslim.windows.resources.screen_mirror_stop
import net.jolabs40.tvslim.windows.resources.screen_record
import net.jolabs40.tvslim.windows.resources.screen_record_stop
import net.jolabs40.tvslim.windows.resources.scrcpy_authorization
import net.jolabs40.tvslim.windows.resources.scrcpy_download
import net.jolabs40.tvslim.windows.resources.scrcpy_downloading
import net.jolabs40.tvslim.windows.resources.scrcpy_text
import net.jolabs40.tvslim.windows.resources.scrcpy_title
import net.jolabs40.tvslim.windows.resources.video_saved
import net.jolabs40.tvslim.windows.resources.video_no_sound
import net.jolabs40.tvslim.windows.resources.video_title
import net.jolabs40.tvslim.windows.ui.CompletedCapture
import net.jolabs40.tvslim.windows.ui.ScreenState
import net.jolabs40.tvslim.windows.ui.RecordingPhase
import net.jolabs40.tvslim.windows.ui.ScrcpyPhase
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
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
fun ScreenActions(
    state: ScreenState,
    connected: Boolean,
    onCapture: () -> Unit,
    onMirror: () -> Unit,
    onStopMirror: () -> Unit,
    onRecord: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val phase = state.scrcpy
    val video = state.recording
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.captureInProgress) {
            Waiting(label = stringResource(Res.string.screen_capture))
        } else {
            ScreenButton(Res.drawable.baseline_photo_camera_24, stringResource(Res.string.screen_capture), connected, onCapture)
        }

        when (phase) {
            is ScrcpyPhase.Downloading -> Waiting(stringResource(Res.string.scrcpy_downloading), phase.progress)
            ScrcpyPhase.Active -> ScreenButton(
                icon = Res.drawable.baseline_screen_share_24,
                label = stringResource(Res.string.screen_mirror_stop),
                active = true,
                onClick = onStopMirror,
                tint = MaterialTheme.colorScheme.primary,
            )

            ScrcpyPhase.Inactive -> ScreenButton(
                icon = Res.drawable.baseline_screen_share_24,
                label = stringResource(Res.string.screen_mirror),
                active = connected && video == RecordingPhase.Inactive,
                onClick = onMirror,
            )
        }

        when (video) {
            RecordingPhase.Inactive -> ScreenButton(
                icon = Res.drawable.baseline_videocam_24,
                label = stringResource(Res.string.screen_record),
                active = connected && phase !is ScrcpyPhase.Downloading,
                onClick = onRecord,
            )

            RecordingPhase.RecordingStart -> Waiting(stringResource(Res.string.record_starting))
            is RecordingPhase.InProgress -> {
                ScreenButton(
                    icon = Res.drawable.baseline_stop_circle_24,
                    label = stringResource(Res.string.screen_record_stop),
                    active = true,
                    onClick = onStopRecording,
                    tint = MaterialTheme.colorScheme.error,
                )
                RecordingTimer(video)
            }

            RecordingPhase.RecordingStop -> Waiting(stringResource(Res.string.record_finishing))
            is RecordingPhase.Copy -> Waiting(stringResource(Res.string.record_finishing), video.progress)
        }
    }
}

/** Elapsed recording time, plus the limit before Android 14. */
@Composable
private fun RecordingTimer(inProgress: RecordingPhase.InProgress) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(inProgress.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = duration(((now - inProgress.startedAt) / 1000).coerceAtLeast(0))
    Text(
        text = inProgress.limitSeconds?.let { "$elapsed / ${duration(it.toLong())}" } ?: elapsed,
        modifier = Modifier.padding(end = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun duration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** A spinner in place of a button during an operation, filled according to [progress]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Waiting(label: String, progress: Float? = null) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (progress == null) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun Tooltip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 4.dp,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** Icon button with its name as a tooltip, since an icon alone is not always clear. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenButton(
    icon: DrawableResource,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    tint: Color = Color.Unspecified,
) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        IconButton(onClick = onClick, enabled = active) {
            if (tint == Color.Unspecified) {
                Icon(painterResource(icon), contentDescription = label)
            } else {
                Icon(painterResource(icon), contentDescription = label, tint = tint)
            }
        }
    }
}

/** Preview of a screenshot just saved. */
@Composable
fun CapturePreviewDialog(
    capture: CompletedCapture,
    onCopy: () -> Unit,
    onOpenFolder: () -> Unit,
    onClose: () -> Unit,
) {
    val image = remember(capture) { ImageSkia.makeFromEncoded(capture.png).toComposeImageBitmap() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(Res.string.capture_title)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = capture.file.name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 405.dp),
                    contentScale = ContentScale.Fit,
                )
                SelectionContainer {
                    Column {
                        Text(
                            text = stringResource(Res.string.capture_saved, capture.file.parent.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        SecondaryText("${capture.file.name} — ${capture.width} × ${capture.height}", small = true)
                    }
                }
                SecondaryText(stringResource(Res.string.capture_drm), small = true)
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenFolder) { Text(stringResource(Res.string.capture_open_folder)) }
                TextButton(onClick = onCopy) { Text(stringResource(Res.string.capture_copy)) }
                Button(onClick = onClose) { Text(stringResource(Res.string.about_close)) }
            }
        },
    )
}

/**
 * scrcpy is missing: what TV Slim will download, from where, and the second authorization the TV will ask for.
 * The same dialog then shows download progress.
 */
@Composable
fun ScrcpyDownloadDialog(
    phase: ScrcpyPhase,
    folder: File,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val inProgress = phase is ScrcpyPhase.Downloading
    AlertDialog(
        onDismissRequest = { if (!inProgress) onCancel() },
        title = { Text(stringResource(Res.string.scrcpy_title)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.scrcpy_text, PinnedScrcpy.VERSION, folder.path))
                Text(stringResource(Res.string.scrcpy_authorization))
                if (phase is ScrcpyPhase.Downloading) {
                    Text(stringResource(Res.string.scrcpy_downloading), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = onDownload, enabled = !inProgress) { Text(stringResource(Res.string.scrcpy_download)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !inProgress) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/** Where the video of a finished recording was saved. */
@Composable
fun RecordedVideoDialog(video: File, onOpenFolder: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(painterResource(Res.drawable.baseline_videocam_24), contentDescription = null) },
        title = { Text(stringResource(Res.string.video_title)) },
        text = {
            // Wide enough for a folder path to fit on one line.
            Box(modifier = Modifier.width(460.dp)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.video_saved, video.parent.orEmpty()), style = MaterialTheme.typography.bodyMedium)
                        SecondaryText(video.name, small = true)
                        SecondaryText(stringResource(Res.string.video_no_sound), small = true)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenFolder) { Text(stringResource(Res.string.capture_open_folder)) }
                Button(onClick = onClose) { Text(stringResource(Res.string.about_close)) }
            }
        },
    )
}

/** Shown when TV Slim is closed during a recording: the video is copied first. */
@Composable
fun ClosingDialog(phase: RecordingPhase) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.closing_title)) },
        text = {
            Column(modifier = Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.closing_text))
                if (phase is RecordingPhase.Copy) {
                    LinearProgressIndicator(progress = { phase.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
    )
}
