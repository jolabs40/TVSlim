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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatRemote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(
    etat: EtatRemote,
    onToutRestaurer: () -> Unit,
    onExporter: () -> Unit,
    onAnnulerAction: (ActionJournal) -> Unit,
) {
    val format = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

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
            Button(onClick = onToutRestaurer, enabled = etat.connecte) {
                Text(stringResource(R.string.journal_restore_all))
            }
            OutlinedButton(onClick = onExporter) {
                Text(stringResource(R.string.journal_export))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

        if (etat.journal.isEmpty()) {
            Text(
                text = stringResource(R.string.journal_empty),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn {
            items(etat.journal.reversed()) { action ->
                VueAction(
                    action = action,
                    horodatage = format.format(Date(action.horodatage)),
                    onAnnuler = { onAnnulerAction(action) },
                    annulable = etat.connecte &&
                        action.reussi &&
                        action.type == TypeAction.DESACTIVATION,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun VueAction(
    action: ActionJournal,
    horodatage: String,
    annulable: Boolean,
    onAnnuler: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${action.libelle} — ${action.cible}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (action.reussi) {
                    stringResource(R.string.journal_success)
                } else {
                    stringResource(R.string.journal_failure, action.message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (action.reussi) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        Text(
            text = "$horodatage · ${action.commandeAnnulation}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Chaque ligne porte déjà sa commande d'annulation : autant pouvoir la jouer seule.
        if (annulable) {
            TextButton(onClick = onAnnuler, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.journal_undo_one))
            }
        }
    }
}
