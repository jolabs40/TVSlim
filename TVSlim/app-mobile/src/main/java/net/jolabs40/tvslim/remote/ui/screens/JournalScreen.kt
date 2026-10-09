package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.RemoteState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(
    state: RemoteState,
    onRestoreAll: () -> Unit,
    onExport: () -> Unit,
    onUndoAction: (JournalAction) -> Unit,
) {
    // Remembered: a SimpleDateFormat is costly to build, and the reversed list would be copied on every recomposition.
    val format = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val recent = remember(state.journal) { state.journal.asReversed() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.journal_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.journal_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRestoreAll, enabled = state.connected) {
                Text(stringResource(R.string.journal_restore_all))
            }
            OutlinedButton(onClick = onExport) {
                Text(stringResource(R.string.journal_export))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        if (state.journal.isEmpty()) {
            Text(
                text = stringResource(R.string.journal_empty),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn {
            items(recent) { action ->
                ActionView(
                    action = action,
                    timestamp = format.format(Date(action.timestamp)),
                    onCancel = { onUndoAction(action) },
                    undoable = state.connected &&
                        action.succeeded &&
                        action.type == ActionType.DISABLING,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ActionView(
    action: JournalAction,
    timestamp: String,
    undoable: Boolean,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Home changes are labeled in the UI language; the label stored in the log is only for export.
            val label = if (action.type == ActionType.HOME) stringResource(R.string.journal_home) else action.label
            Text(
                text = "$label — ${action.target}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (action.succeeded) {
                    stringResource(R.string.journal_success)
                } else {
                    stringResource(R.string.journal_failure, action.message.ifBlank { stringResource(R.string.engine_unexplained) })
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (action.succeeded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        // An install has no undo command, so only the timestamp remains.
        Text(
            text = listOf(timestamp, action.undoCommand).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Each entry carries its own undo command, so it can be replayed alone.
        if (undoable) {
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.journal_undo_one))
            }
        }
    }
}
