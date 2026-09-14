package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.commande.EchangeCommande
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.command_cut_connection
import net.jolabs40.tvslim.windows.ressources.command_cut_delay
import net.jolabs40.tvslim.windows.ressources.command_exit
import net.jolabs40.tvslim.windows.ressources.command_keys
import net.jolabs40.tvslim.windows.ressources.command_label
import net.jolabs40.tvslim.windows.ressources.command_no_output
import net.jolabs40.tvslim.windows.ressources.command_placeholder
import net.jolabs40.tvslim.windows.ressources.command_running
import net.jolabs40.tvslim.windows.ressources.command_send
import net.jolabs40.tvslim.windows.ressources.command_title
import net.jolabs40.tvslim.windows.ressources.command_truncated
import net.jolabs40.tvslim.windows.ressources.command_warning
import net.jolabs40.tvslim.windows.ui.ActionsCommande
import net.jolabs40.tvslim.windows.ui.EtatCommande
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource

/**
 * Une commande du shell du téléviseur, tapée à la main : pour ce que les autres cartes ne font pas.
 *
 * Elle échappe aux garde-fous, et la carte le dit avant tout. Entrée envoie, ↑ et ↓ rappellent les
 * commandes précédentes ; la sortie reste affichée, sélectionnable, jusqu'à la suivante.
 */
@Composable
fun CarteCommande(etat: EtatCommande, actions: ActionsCommande) {
    CarteSection(titre = stringResource(Res.string.command_title), espacement = 12.dp) {
        Text(
            text = stringResource(Res.string.command_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        OutlinedTextField(
            value = etat.saisie,
            onValueChange = actions.onSaisie,
            label = { Text(stringResource(Res.string.command_label)) },
            placeholder = { Text(stringResource(Res.string.command_placeholder), fontFamily = FontFamily.Monospace) },
            supportingText = { Text(stringResource(Res.string.command_keys)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { evenement ->
                if (evenement.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (evenement.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        actions.onEnvoyer()
                        true
                    }

                    Key.DirectionUp -> {
                        actions.onRappel(true)
                        true
                    }

                    Key.DirectionDown -> {
                        actions.onRappel(false)
                        true
                    }

                    else -> false
                }
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = actions.onEnvoyer, enabled = !etat.enCours && etat.saisie.isNotBlank()) {
                Text(stringResource(Res.string.command_send))
            }
            if (etat.enCours) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                TexteSecondaire(stringResource(Res.string.command_running, ConsoleAdb.DELAI_MAX_S), petit = true)
            }
        }

        etat.derniere?.let { Sortie(it) }
    }
}

/** La commande, comment elle s'est terminée, et ce qu'elle a écrit — même coupée. */
@Composable
private fun Sortie(echange: EchangeCommande) {
    val (statut, couleur) = when {
        echange.interruption == Interruption.DELAI ->
            stringResource(Res.string.command_cut_delay, ConsoleAdb.DELAI_MAX_S) to MaterialTheme.colorScheme.error

        echange.interruption == Interruption.CONNEXION ->
            stringResource(Res.string.command_cut_connection) to MaterialTheme.colorScheme.error

        echange.reussie -> stringResource(Res.string.command_exit, 0) to MaterialTheme.colorScheme.primary
        else -> stringResource(Res.string.command_exit, echange.code ?: -1) to MaterialTheme.colorScheme.error
    }
    val defilement = remember(echange) { ScrollState(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "$ ${echange.commande}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = statut, style = MaterialTheme.typography.labelMedium, color = couleur)
            }
            if (echange.interruption == Interruption.CONNEXION && echange.motif.isNotBlank()) {
                TexteSecondaire(echange.motif, petit = true)
            }
            SelectionContainer {
                Text(
                    text = echange.sortie.ifEmpty { stringResource(Res.string.command_no_output) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(defilement),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (echange.tronquee) {
                TexteSecondaire(
                    stringResource(Res.string.command_truncated, echange.sortie.length, echange.longueurRecue),
                    petit = true,
                )
            }
        }
    }
}
