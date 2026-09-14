package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.commande.EchangeCommande
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ActionsCommande
import net.jolabs40.tvslim.remote.ui.EtatCommande
import net.jolabs40.tvslim.shell.Interruption

/**
 * Une commande du shell du téléviseur, tapée à la main : pour ce que les autres cartes ne font pas.
 *
 * Elle échappe aux garde-fous, et la carte le dit avant tout. Le clavier ne corrige rien — une commande
 * n'est pas une phrase —, l'historique est au bout du champ, et la sortie reste affichée, sélectionnable,
 * jusqu'à la suivante.
 */
@Composable
fun CarteCommande(etat: EtatCommande, actions: ActionsCommande) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.command_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.command_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            OutlinedTextField(
                value = etat.saisie,
                onValueChange = actions.onSaisie,
                label = { Text(stringResource(R.string.command_label)) },
                placeholder = { Text(stringResource(R.string.command_placeholder), fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { actions.onEnvoyer() }),
                trailingIcon = if (etat.historique.isEmpty()) null else {
                    { Historique(etat.historique, actions.onSaisie) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = actions.onEnvoyer, enabled = !etat.enCours && etat.saisie.isNotBlank()) {
                    Text(stringResource(R.string.command_send))
                }
                if (etat.enCours) CircularProgressIndicator()
            }
            if (etat.enCours) {
                Text(
                    text = stringResource(R.string.command_running, ConsoleAdb.DELAI_MAX_S),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            etat.derniere?.let { Sortie(it) }
        }
    }
}

/** Les commandes déjà envoyées, à reprendre d'un toucher plutôt qu'à retaper. */
@Composable
private fun Historique(historique: List<String>, onChoisir: (String) -> Unit) {
    var ouvert by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { ouvert = true }) {
            Icon(Icons.Filled.History, contentDescription = stringResource(R.string.command_history))
        }
        DropdownMenu(expanded = ouvert, onDismissRequest = { ouvert = false }) {
            historique.forEach { precedente ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = precedente,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        ouvert = false
                        onChoisir(precedente)
                    },
                )
            }
        }
    }
}

/** La commande, comment elle s'est terminée, et ce qu'elle a écrit — même coupée. */
@Composable
private fun Sortie(echange: EchangeCommande) {
    val (statut, couleur) = when {
        echange.interruption == Interruption.DELAI ->
            stringResource(R.string.command_cut_delay, ConsoleAdb.DELAI_MAX_S) to MaterialTheme.colorScheme.error

        echange.interruption == Interruption.CONNEXION ->
            stringResource(R.string.command_cut_connection) to MaterialTheme.colorScheme.error

        echange.reussie -> stringResource(R.string.command_exit, 0) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.command_exit, echange.code ?: -1) to MaterialTheme.colorScheme.error
    }
    val defilement = remember(echange) { ScrollState(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "$ ${echange.commande}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = statut, style = MaterialTheme.typography.labelMedium, color = couleur)
            if (echange.interruption == Interruption.CONNEXION && echange.motif.isNotBlank()) {
                Text(
                    text = echange.motif,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(
                    text = echange.sortie.ifEmpty { stringResource(R.string.command_no_output) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(defilement),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (echange.tronquee) {
                Text(
                    text = stringResource(R.string.command_truncated, echange.sortie.length, echange.longueurRecue),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
