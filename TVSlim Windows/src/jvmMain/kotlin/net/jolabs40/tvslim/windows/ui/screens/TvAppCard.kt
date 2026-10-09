package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import net.jolabs40.tvslim.tvapp.TvStep
import net.jolabs40.tvslim.tvapp.TvAppState
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.tvapp_absent
import net.jolabs40.tvslim.windows.resources.tvapp_body
import net.jolabs40.tvslim.windows.resources.tvapp_grant_action
import net.jolabs40.tvslim.windows.resources.tvapp_how
import net.jolabs40.tvslim.windows.resources.tvapp_install
import net.jolabs40.tvslim.windows.resources.tvapp_install_version
import net.jolabs40.tvslim.windows.resources.tvapp_no_permission
import net.jolabs40.tvslim.windows.resources.tvapp_reading
import net.jolabs40.tvslim.windows.resources.tvapp_step_download
import net.jolabs40.tvslim.windows.resources.tvapp_step_guardian
import net.jolabs40.tvslim.windows.resources.tvapp_step_permission
import net.jolabs40.tvslim.windows.resources.tvapp_step_search
import net.jolabs40.tvslim.windows.resources.tvapp_step_send
import net.jolabs40.tvslim.windows.resources.tvapp_step_verify
import net.jolabs40.tvslim.windows.resources.tvapp_title
import net.jolabs40.tvslim.windows.resources.tvapp_up_to_date
import net.jolabs40.tvslim.windows.resources.tvapp_update
import net.jolabs40.tvslim.windows.resources.tvapp_update_action
import net.jolabs40.tvslim.windows.ui.TvAppActions
import net.jolabs40.tvslim.windows.ui.TvAppUiState
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.stringResource

/**
 * The TV Slim app on the TV: what it does, its status, and a single button to install, update or
 * re-authorize it. Same card as on the phone.
 */
@Composable
fun TvAppCard(state: TvAppUiState, host: String, actions: TvAppActions) {
    // Re-read for each TV; the card is only shown for a TV or a box.
    LaunchedEffect(host) { actions.onRead() }

    SectionCard(title = stringResource(Res.string.tvapp_title)) {
        Text(text = stringResource(Res.string.tvapp_body), style = MaterialTheme.typography.bodyMedium)

        val step = state.step
        val situation = state.situation
        when {
            step != null -> LabeledProgress(step)
            situation == null -> SecondaryText(stringResource(Res.string.tvapp_reading))
            else -> {
                val installed = situation.installed?.versionName.orEmpty()
                val available = situation.available?.version
                Text(
                    text = when (situation.state) {
                        TvAppState.MISSING -> stringResource(Res.string.tvapp_absent)
                        TvAppState.UPDATE -> stringResource(Res.string.tvapp_update, installed, available.orEmpty())
                        TvAppState.NO_PERMISSION -> stringResource(Res.string.tvapp_no_permission, installed)
                        TvAppState.UP_TO_DATE -> stringResource(Res.string.tvapp_up_to_date, installed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                when (situation.state) {
                    TvAppState.MISSING -> {
                        SecondaryText(stringResource(Res.string.tvapp_how))
                        Button(onClick = actions.onInstall) {
                            Text(
                                if (available != null) {
                                    stringResource(Res.string.tvapp_install_version, available)
                                } else {
                                    stringResource(Res.string.tvapp_install)
                                },
                            )
                        }
                    }
                    TvAppState.UPDATE -> Button(onClick = actions.onInstall) {
                        Text(stringResource(Res.string.tvapp_update_action))
                    }
                    TvAppState.NO_PERMISSION -> Button(onClick = actions.onAuthorize) {
                        Text(stringResource(Res.string.tvapp_grant_action))
                    }
                    TvAppState.UP_TO_DATE -> Unit
                }
            }
        }
    }
}

@Composable
private fun LabeledProgress(step: TvStep) {
    val (text, fraction) = when (step) {
        TvStep.Checking -> stringResource(Res.string.tvapp_step_search) to null
        is TvStep.Downloading -> part(step.receivedBytes, step.total).let {
            stringResource(Res.string.tvapp_step_download, ((it ?: 0f) * 100).toInt()) to it
        }
        TvStep.Verification -> stringResource(Res.string.tvapp_step_verify) to null
        is TvStep.Upload -> part(step.sent, step.total).let {
            stringResource(Res.string.tvapp_step_send, ((it ?: 0f) * 100).toInt()) to it
        }
        TvStep.Authorization -> stringResource(Res.string.tvapp_step_permission) to null
        TvStep.Guardian -> stringResource(Res.string.tvapp_step_guardian) to null
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun part(done: Long, total: Long): Float? = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
