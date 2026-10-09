package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.ui.DisplayedDrift
import net.jolabs40.tvslim.ui.UiState
import net.jolabs40.tvslim.ui.components.MessageStrip
import net.jolabs40.tvslim.ui.components.InfoBlock
import net.jolabs40.tvslim.ui.components.Header
import net.jolabs40.tvslim.ui.components.MeasurementRow

@Composable
fun HomeScreen(
    state: UiState,
    onSettings: () -> Unit,
    onPairing: () -> Unit,
    onPackages: () -> Unit,
    onRefresh: () -> Unit,
    onCloseMessage: () -> Unit,
) {
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstButton.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Header(
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.home_subtitle),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InfoBlock(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                MeasurementRow(
                    stringResource(R.string.device_model),
                    "${state.info.brand} ${state.info.model}",
                )
                MeasurementRow(stringResource(R.string.device_android), state.info.androidVersion)
                MeasurementRow(
                    stringResource(R.string.device_memory),
                    stringResource(
                        R.string.device_memory_value,
                        state.info.freeMemoryMb,
                        state.info.totalMemoryMb,
                    ),
                )
                MeasurementRow(
                    stringResource(R.string.device_packages_active),
                    state.info.installedPackages.toString(),
                )
                MeasurementRow(
                    stringResource(R.string.device_packages_disabled),
                    state.info.disabledPackages.toString(),
                )
                MeasurementRow(
                    stringResource(R.string.device_home),
                    state.info.currentHome.ifBlank { "—" },
                )
            }

            InfoBlock(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.companion_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.companion_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        if (state.guardianActive) {
                            R.string.watchdog_on
                        } else {
                            R.string.watchdog_off
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.guardianActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        // A system update undid part of the debloat: show that first.
        state.drift?.let { drift ->
            Spacer(Modifier.height(16.dp))
            DriftBlock(drift)
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPairing, modifier = Modifier.focusRequester(firstButton)) {
                Text(stringResource(R.string.action_pairing))
            }
            Button(onClick = onPackages) { Text(stringResource(R.string.action_packages)) }
            Button(onClick = onSettings) { Text(stringResource(R.string.action_settings)) }
            Button(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
        }

        state.message?.let { message ->
            Spacer(Modifier.height(16.dp))
            MessageStrip(message = message, onClose = onCloseMessage)
        }

        if (state.loading) {
            Text(
                text = stringResource(R.string.common_working),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the last system update undid (see `DriftGuardian`). Nothing to select here: the fix is done from the
 * phone or PC, which have the log and the ADB session.
 */
@Composable
private fun DriftBlock(drift: DisplayedDrift) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.drift_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (drift.reenabled.isNotEmpty()) {
            Text(
                text = pluralStringResource(R.plurals.drift_packages, drift.reenabled.size, drift.reenabled.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = drift.reenabled.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        drift.lostHome?.let { lost ->
            Text(
                text = stringResource(R.string.drift_home, lost),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Text(
            text = stringResource(R.string.drift_fix),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
