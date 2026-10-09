package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.InstallationState
import net.jolabs40.tvslim.remote.ui.InstallationPhase
import net.jolabs40.tvslim.remote.ui.megabytes
import net.jolabs40.tvslim.remote.ui.resource

/**
 * Installs an APK from the phone onto the TV, like `adb install` without a computer.
 *
 * The last result stays on the card: the banner goes away, and an Android refusal (raw response included) is worth
 * rereading.
 */
@Composable
fun InstallationCard(state: InstallationState, onChoose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.install_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.install_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onChoose, enabled = !state.busy) {
                Icon(Icons.Filled.GetApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.install_choose))
            }

            when (val phase = state.phase) {
                null -> state.last?.let { Summary(it) }
                InstallationPhase.Review -> LabeledProgress(stringResource(R.string.install_examining), fraction = null)
                is InstallationPhase.Upload -> LabeledProgress(
                    text = stringResource(R.string.install_sending, megabytes(phase.sent), megabytes(phase.total)),
                    fraction = if (phase.total > 0) phase.sent.toFloat() / phase.total else null,
                )
                // Fully sent: Android verifies the app, and the TV may ask for confirmation.
                InstallationPhase.Installation ->
                    LabeledProgress(stringResource(R.string.install_installing), fraction = null)
            }
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
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Summary(result: InstallationResult) {
    val succeeded = result is InstallationResult.Succeeded
    val color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = if (succeeded) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = color,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (succeeded) R.string.install_last_success else R.string.install_last_failure,
                    result.apk.name,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
            Text(
                text = identity(result.apk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (result is InstallationResult.Failed) {
                Text(text = stringResource(result.cause.resource()), style = MaterialTheme.typography.bodyMedium)
                // Empty when the TV sent no response.
                if (result.detail.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = result.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** `net.jolabs40.hippietv · 2.4.0` */
private fun identity(apk: ChosenApk): String =
    listOf(apk.manifest.packageName, apk.manifest.versionName).filter { it.isNotBlank() }.joinToString(" · ")
