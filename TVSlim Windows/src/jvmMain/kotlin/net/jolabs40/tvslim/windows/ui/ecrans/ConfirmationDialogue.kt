package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.confirm_apply_body
import net.jolabs40.tvslim.windows.ressources.confirm_apply_title
import net.jolabs40.tvslim.windows.ressources.confirm_cancel
import net.jolabs40.tvslim.windows.ressources.confirm_go
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_disable
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_enable
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_home
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_home_missing
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_ignored
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_source
import net.jolabs40.tvslim.windows.ressources.confirm_reinject_title
import net.jolabs40.tvslim.windows.ressources.confirm_restore_body
import net.jolabs40.tvslim.windows.ressources.confirm_restore_title
import net.jolabs40.tvslim.windows.ui.Confirmation
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Dernier arrêt avant d'agir.
 *
 * Le catalogue connaît les effets de bord — « la touche Netflix devient inopérante » — et ils sont
 * affichés ici, au moment de décider. Rien ne part tant que ce n'est pas validé.
 */
@Composable
fun ConfirmationDialogue(
    confirmation: Confirmation,
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = {
            Text(
                stringResource(
                    when (confirmation) {
                        is Confirmation.Application -> Res.string.confirm_apply_title
                        is Confirmation.Restauration -> Res.string.confirm_restore_title
                        is Confirmation.Reinjection -> Res.string.confirm_reinject_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (confirmation) {
                    is Confirmation.Application -> {
                        Text(
                            text = stringResource(Res.string.confirm_apply_body, confirmation.entrees.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        confirmation.entrees.forEach { entree ->
                            Text(
                                text = "• ${entree.nom}",
                                modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            entree.effetDeBord?.let { effet ->
                                Text(
                                    text = effet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    is Confirmation.Restauration -> {
                        Text(
                            text = stringResource(Res.string.confirm_restore_body, confirmation.paquets.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        confirmation.paquets.forEach { paquet ->
                            Text(
                                text = "• $paquet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is Confirmation.Reinjection -> Reinjection(confirmation.plan)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmer) { Text(stringResource(Res.string.confirm_go)) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/**
 * Ce que la configuration va changer, rangé par nature : ce qui revient, ce qui part — effets de bord
 * compris —, l'écran d'accueil, et ce que ce téléviseur n'a pas.
 */
@Composable
private fun Reinjection(plan: PlanReinjection) {
    val sauvegarde = plan.configuration
    val date = remember(sauvegarde.sauvegardeLe) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(sauvegarde.sauvegardeLe).atZone(ZoneId.systemDefault()))
    }
    Text(
        text = stringResource(Res.string.confirm_reinject_source, date, sauvegarde.appareil.nom.ifBlank { "—" }),
        style = MaterialTheme.typography.bodyMedium,
    )

    if (plan.aReactiver.isNotEmpty()) {
        Intertitre(stringResource(Res.string.confirm_reinject_enable, plan.aReactiver.size))
        plan.aReactiver.forEach { Puce(it.nom) }
    }

    if (plan.aDesactiver.isNotEmpty()) {
        Intertitre(stringResource(Res.string.confirm_reinject_disable, plan.aDesactiver.size))
        plan.aDesactiver.forEach { entree ->
            Puce(entree.nom)
            entree.effetDeBord?.let { effet ->
                Text(text = effet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    plan.accueil?.let { accueil ->
        if (accueil.possible) {
            Intertitre(stringResource(Res.string.confirm_reinject_home, accueil.nom))
        } else {
            Text(
                text = stringResource(Res.string.confirm_reinject_home_missing, accueil.nom),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (plan.ignores.isNotEmpty()) {
        Text(
            text = stringResource(Res.string.confirm_reinject_ignored, plan.ignores.size),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Intertitre(texte: String) {
    Text(
        text = texte,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Puce(texte: String) {
    Text(text = "• $texte", style = MaterialTheme.typography.bodyMedium)
}
