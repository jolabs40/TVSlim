package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.components.Bandeau
import net.jolabs40.tvslim.ui.components.EnTete
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JournalScreen(
    etat: EtatUi,
    onToutRestaurer: () -> Unit,
    onExporter: () -> Unit,
    onFermerMessage: () -> Unit,
) {
    val format = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.journal_title),
            sousTitre = stringResource(R.string.journal_subtitle),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onToutRestaurer) {
                Text(stringResource(R.string.journal_restore_all))
            }
            Button(onClick = onExporter) { Text(stringResource(R.string.journal_export)) }
        }

        etat.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Bandeau(message = message, onFermer = onFermerMessage)
        }

        Spacer(Modifier.height(16.dp))

        if (etat.journal.isEmpty()) {
            Text(
                text = stringResource(R.string.journal_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(etat.journal.reversed()) { action ->
                VueAction(action = action, horodatage = format.format(Date(action.horodatage)))
            }
        }
    }
}

@Composable
private fun VueAction(action: ActionJournal, horodatage: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = horodatage,
            modifier = Modifier.padding(end = 14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${action.libelle} — ${action.cible}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = action.commandeAnnulation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
}
