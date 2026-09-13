package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.journal_empty
import net.jolabs40.tvslim.windows.ressources.journal_export
import net.jolabs40.tvslim.windows.ressources.journal_failure
import net.jolabs40.tvslim.windows.ressources.journal_not_connected
import net.jolabs40.tvslim.windows.ressources.journal_restore_all
import net.jolabs40.tvslim.windows.ressources.journal_subtitle
import net.jolabs40.tvslim.windows.ressources.journal_success
import net.jolabs40.tvslim.windows.ressources.journal_title
import net.jolabs40.tvslim.windows.ressources.journal_undo_one
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Les actions dont le pilote sait jouer la commande d'annulation, ligne par ligne. */
private val ANNULABLES = setOf(TypeAction.DESACTIVATION, TypeAction.PERMISSION, TypeAction.APP_OP)

/**
 * Le journal du téléviseur joint : chaque action, avec la commande exacte qui l'annule. C'est lui
 * qui rend l'intervention réversible — tout restaurer, ou une seule ligne.
 */
@Composable
fun JournalEcran(
    etat: EtatApp,
    onToutRestaurer: () -> Unit,
    onExporter: () -> Unit,
    onAnnulerAction: (ActionJournal) -> Unit,
) {
    if (!etat.connecte) {
        EcranVide(stringResource(Res.string.journal_not_connected))
        return
    }

    val format = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val recentes = remember(etat.journal) { etat.journal.asReversed() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(Res.string.journal_title), style = MaterialTheme.typography.titleLarge)
                TexteSecondaire(stringResource(Res.string.journal_subtitle))
            }
            OutlinedButton(onClick = onExporter, enabled = etat.journal.isNotEmpty()) {
                Text(stringResource(Res.string.journal_export))
            }
            Button(onClick = onToutRestaurer) {
                Text(stringResource(Res.string.journal_restore_all))
            }
        }

        HorizontalDivider()

        if (etat.journal.isEmpty()) {
            TexteSecondaire(stringResource(Res.string.journal_empty), modifier = Modifier.padding(20.dp))
            return@Column
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val liste = rememberLazyListState()
            LazyColumn(state = liste, modifier = Modifier.fillMaxSize()) {
                items(recentes) { action ->
                    VueAction(
                        action = action,
                        horodatage = format.format(Date(action.horodatage)),
                        annulable = action.reussi && action.type in ANNULABLES,
                        onAnnuler = { onAnnulerAction(action) },
                    )
                    HorizontalDivider()
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(liste),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = "${action.libelle} — ${action.cible}", style = MaterialTheme.typography.bodyMedium)
            // La commande d'annulation se copie : elle se rejoue aussi bien depuis un terminal.
            SelectionContainer {
                TexteSecondaire("$horodatage · ${action.commandeAnnulation}", petit = true)
            }
        }
        Text(
            text = if (action.reussi) {
                stringResource(Res.string.journal_success)
            } else {
                stringResource(Res.string.journal_failure, action.message)
            },
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = 320.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (action.reussi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (annulable) {
            TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.journal_undo_one)) }
        }
    }
}
