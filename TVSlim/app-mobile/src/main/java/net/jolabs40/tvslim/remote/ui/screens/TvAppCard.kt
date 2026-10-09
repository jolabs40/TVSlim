package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.tvapp.TvStep
import net.jolabs40.tvslim.tvapp.TvAppState
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.TvAppActions
import net.jolabs40.tvslim.remote.ui.TvAppUiState

/** The TV app's status, with a single button to install it, update it or grant its permission again. */
@Composable
fun TvAppCard(state: TvAppUiState, host: String, actions: TvAppActions) {
    // Re-read for each device. The card is only shown for a TV or a box.
    LaunchedEffect(host) { actions.onRead() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tvapp_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(R.string.tvapp_body), style = MaterialTheme.typography.bodyMedium)

            val step = state.step
            val situation = state.situation
            when {
                step != null -> LabeledProgress(step)
                situation == null -> Text(
                    text = stringResource(R.string.tvapp_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val installed = situation.installed?.versionName.orEmpty()
                    val available = situation.available?.version
                    Text(
                        text = when (situation.state) {
                            TvAppState.MISSING -> stringResource(R.string.tvapp_absent)
                            TvAppState.UPDATE -> stringResource(R.string.tvapp_update, installed, available.orEmpty())
                            TvAppState.NO_PERMISSION -> stringResource(R.string.tvapp_no_permission, installed)
                            TvAppState.UP_TO_DATE -> stringResource(R.string.tvapp_up_to_date, installed)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    when (situation.state) {
                        TvAppState.MISSING -> {
                            Text(
                                text = stringResource(R.string.tvapp_how),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = actions.onInstall) {
                                Text(
                                    if (available != null) {
                                        stringResource(R.string.tvapp_install_version, available)
                                    } else {
                                        stringResource(R.string.tvapp_install)
                                    },
                                )
                            }
                        }
                        TvAppState.UPDATE -> Button(onClick = actions.onInstall) {
                            Text(stringResource(R.string.tvapp_update_action))
                        }
                        TvAppState.NO_PERMISSION -> Button(onClick = actions.onAuthorize) {
                            Text(stringResource(R.string.tvapp_grant_action))
                        }
                        TvAppState.UP_TO_DATE -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledProgress(step: TvStep) {
    val (text, fraction) = when (step) {
        TvStep.Checking -> stringResource(R.string.tvapp_step_search) to null
        is TvStep.Downloading -> part(step.receivedBytes, step.total).let {
            stringResource(R.string.tvapp_step_download, ((it ?: 0f) * 100).toInt()) to it
        }
        TvStep.Verification -> stringResource(R.string.tvapp_step_verify) to null
        is TvStep.Upload -> part(step.sent, step.total).let {
            stringResource(R.string.tvapp_step_send, ((it ?: 0f) * 100).toInt()) to it
        }
        TvStep.Authorization -> stringResource(R.string.tvapp_step_permission) to null
        TvStep.Guardian -> stringResource(R.string.tvapp_step_guardian) to null
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun part(done: Long, total: Long): Float? = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
