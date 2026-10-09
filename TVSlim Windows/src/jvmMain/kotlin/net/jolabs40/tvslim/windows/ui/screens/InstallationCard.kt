package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_check_circle_24
import net.jolabs40.tvslim.windows.resources.baseline_error_24
import net.jolabs40.tvslim.windows.resources.baseline_get_app_24
import net.jolabs40.tvslim.windows.resources.baseline_upload_file_24
import net.jolabs40.tvslim.windows.resources.files_drop
import net.jolabs40.tvslim.windows.resources.files_not_connected
import net.jolabs40.tvslim.windows.resources.install_choose
import net.jolabs40.tvslim.windows.resources.install_drop
import net.jolabs40.tvslim.windows.resources.install_drop_disconnected
import net.jolabs40.tvslim.windows.resources.install_examining
import net.jolabs40.tvslim.windows.resources.install_hint
import net.jolabs40.tvslim.windows.resources.install_installing
import net.jolabs40.tvslim.windows.resources.install_last_failure
import net.jolabs40.tvslim.windows.resources.install_last_success
import net.jolabs40.tvslim.windows.resources.install_sending
import net.jolabs40.tvslim.windows.resources.install_title
import net.jolabs40.tvslim.windows.ui.InstallationState
import net.jolabs40.tvslim.windows.ui.InstallationPhase
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import net.jolabs40.tvslim.windows.ui.megabytes
import net.jolabs40.tvslim.windows.ui.resource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Installs an APK on the TV, picked in Explorer or dropped on the window: what `adb install` does, without
 * `adb.exe`.
 *
 * The last result stays on the card, raw reply included, since the snackbar does not last and an Android
 * refusal is worth rereading.
 */
@Composable
fun InstallationCard(state: InstallationState, onChoose: () -> Unit) {
    SectionCard(title = stringResource(Res.string.install_title), spacing = 12.dp) {
        SecondaryText(stringResource(Res.string.install_hint))
        Button(onClick = onChoose, enabled = !state.busy) {
            Icon(
                painter = painterResource(Res.drawable.baseline_get_app_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.install_choose))
        }

        when (val phase = state.phase) {
            null -> state.last?.let { Summary(it) }
            InstallationPhase.Review -> LabeledProgress(stringResource(Res.string.install_examining), fraction = null)
            is InstallationPhase.Upload -> LabeledProgress(
                text = stringResource(Res.string.install_sending, megabytes(phase.sent), megabytes(phase.total)),
                fraction = if (phase.total > 0) phase.sent.toFloat() / phase.total else null,
            )
            // Upload done: Android verifies the app, and the TV may ask for confirmation.
            InstallationPhase.Installation -> LabeledProgress(stringResource(Res.string.install_installing), fraction = null)
        }
    }
}

@Composable
private fun LabeledProgress(text: String, fraction: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        SecondaryText(text, small = true)
    }
}

@Composable
private fun Summary(result: InstallationResult) {
    val succeeded = result is InstallationResult.Succeeded
    val color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painter = painterResource(if (succeeded) Res.drawable.baseline_check_circle_24 else Res.drawable.baseline_error_24),
            contentDescription = null,
            tint = color,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (succeeded) Res.string.install_last_success else Res.string.install_last_failure,
                    result.apk.name,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            SecondaryText(identity(result.apk), small = true)
            if (result is InstallationResult.Failed) {
                Text(text = stringResource(result.cause.resource()), style = MaterialTheme.typography.bodyMedium)
                // Empty when the TV gave no answer; the cause is enough then.
                if (result.detail.isNotBlank()) SelectionContainer { SecondaryText(result.detail, small = true) }
            }
        }
    }
}

/** "net.jolabs40.hippietv · 2.4.0" */
private fun identity(apk: ChosenApk): String =
    listOf(apk.manifest.packageName, apk.manifest.versionName).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * Overlay shown while a file is dragged over the window: where it will go, or that a connection is needed.
 * With a [destination] (Files tab), the drop is an upload to that folder, not an install.
 */
@Composable
fun UploadOverlay(connected: Boolean, tvName: String, destination: String? = null) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f), shape)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(
                    if (destination != null) Res.drawable.baseline_upload_file_24 else Res.drawable.baseline_get_app_24,
                ),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = when {
                    !connected && destination != null -> stringResource(Res.string.files_not_connected)
                    !connected -> stringResource(Res.string.install_drop_disconnected)
                    destination != null -> stringResource(Res.string.files_drop, destination, tvName)
                    else -> stringResource(Res.string.install_drop, tvName)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
