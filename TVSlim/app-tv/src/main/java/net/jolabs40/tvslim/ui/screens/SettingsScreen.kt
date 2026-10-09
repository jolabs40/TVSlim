package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.ui.UiState
import net.jolabs40.tvslim.ui.SettingRow
import net.jolabs40.tvslim.ui.components.MessageStrip
import net.jolabs40.tvslim.ui.components.ScrollBar
import net.jolabs40.tvslim.ui.components.InfoBlock
import net.jolabs40.tvslim.ui.components.ListCounter
import net.jolabs40.tvslim.ui.components.Header
import net.jolabs40.tvslim.ui.components.FocusableRow

@Composable
fun SettingsScreen(
    state: UiState,
    onToggleSetting: (SettingRow) -> Unit,
    onGuardian: (Boolean) -> Unit,
    onCloseMessage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Header(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        InfoBlock(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_direct_write_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (state.directWrite) {
                        R.string.settings_direct_write_yes
                    } else {
                        R.string.settings_direct_write_no
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.directWrite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_adb_grant_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            MessageStrip(message = message, onClose = onCloseMessage)
        }

        Spacer(Modifier.height(16.dp))

        val listState = rememberLazyListState()
        val total = state.settings.size + 1 // + le gardien de démarrage

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_list_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            ListCounter(state = listState, total = total)
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "gardien") {
                    FocusableRow(onClick = { onGuardian(!state.guardianActive) }) {
                        ToggleRow(
                            checked = state.guardianActive,
                            title = stringResource(R.string.settings_watchdog_title),
                            description = stringResource(R.string.settings_watchdog_desc),
                        )
                    }
                }

                items(state.settings, key = { it.setting.key }) { line ->
                    FocusableRow(onClick = { onToggleSetting(line) }) {
                        ToggleRow(
                            checked = line.optimized,
                            title = line.setting.name,
                            description = line.setting.description,
                            rawValue = stringResource(
                                R.string.settings_current_value,
                                line.currentValue ?: "—",
                            ),
                        )
                    }
                }
            }

            ScrollBar(
                state = listState,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .width(4.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    title: String,
    description: String,
    rawValue: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (checked) "☑" else "☐",
            modifier = Modifier.padding(end = 14.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rawValue != null) {
            Text(
                text = rawValue,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
