package net.jolabs40.tvslim.remote.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.applications.ApplicationAppareil
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ConfirmationApplication
import net.jolabs40.tvslim.remote.ui.EtatApplications

/** Ce que l'onglet Applications demande au pilote. */
class ActionsApplicationsUi(
    val onCharger: () -> Unit,
    val onRecherche: (String) -> Unit,
    val onChoisir: (ApplicationAppareil?) -> Unit,
    val onOuvrir: (ApplicationAppareil) -> Unit,
    val onArreter: (ApplicationAppareil) -> Unit,
    val onDesactiver: (ApplicationAppareil) -> Unit,
    val onReactiver: (ApplicationAppareil) -> Unit,
    val onDesinstaller: (ApplicationAppareil) -> Unit,
    val onConfirmer: () -> Unit,
    val onAnnuler: () -> Unit,
    /** L'entrée du catalogue qui permet de désactiver une application ; `null` : elle ne se désactive pas d'ici. */
    val desactivable: (ApplicationAppareil) -> EntreePaquet?,
)

/**
 * L'onglet Applications du compagnon : la liste, avec icônes et noms ; un appui ouvre les actions de l'application.
 * La première lecture se lance d'elle-même, les noms et les icônes arrivent au fil de l'eau.
 */
@Composable
fun ApplicationsScreen(connecte: Boolean, etat: EtatApplications, actions: ActionsApplicationsUi) {
    if (!connecte) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.apps_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LaunchedEffect(Unit) { if (!etat.lue && !etat.chargement) actions.onCharger() }

    etat.choisie?.let { ActionsDialogue(it, actions.desactivable(it) != null, etat.occupee != null, actions) }
    etat.confirmation?.let { ConfirmationDialogue(it, actions) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = etat.recherche,
                onValueChange = actions.onRecherche,
                label = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.onCharger, enabled = !etat.chargement) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.apps_count, etat.applications.size, etat.nombreDesactivees),
                style = MaterialTheme.typography.bodySmall,
            )
            if (etat.chargement) {
                val avancee = etat.avancee
                if (avancee == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.apps_reading_list), style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(
                        progress = { avancee.first.toFloat() / avancee.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.apps_reading, avancee.first, avancee.second), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                text = stringResource(R.string.apps_helper_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(etat.affichees, key = { it.paquet }) { application ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onChoisir(application) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconeApplication(application, 40)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = application.nom,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = application.paquet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Etiquettes(application)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Etiquettes(application: ApplicationAppareil) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (application.systeme) R.string.apps_system else R.string.apps_installed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!application.active) {
            Text(
                text = stringResource(R.string.apps_disabled),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** L'icône lue sur l'appareil, ou le robot d'Android tant qu'elle ne l'est pas. Le choix d'une application, dans les permissions, s'en sert aussi. */
@Composable
internal fun IconeApplication(application: ApplicationAppareil, taille: Int) {
    val image: ImageBitmap? = remember(application.paquet, application.icone) {
        application.icone?.let { octets -> BitmapFactory.decodeByteArray(octets, 0, octets.size)?.asImageBitmap() }
    }
    Box(modifier = Modifier.size(taille.dp), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.size(taille.dp))
        } else {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size((taille * 0.7).dp),
            )
        }
    }
}

/** Les actions d'une application, selon ce qu'elle permet. */
@Composable
private fun ActionsDialogue(
    application: ApplicationAppareil,
    desactivable: Boolean,
    occupee: Boolean,
    actions: ActionsApplicationsUi,
) {
    AlertDialog(
        onDismissRequest = { actions.onChoisir(null) },
        icon = { IconeApplication(application, 48) },
        title = { Text(application.nom) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(application.paquet, style = MaterialTheme.typography.bodySmall)
                Etiquettes(application)
                val libre = !occupee
                TextButton(
                    onClick = { actions.onOuvrir(application) },
                    enabled = libre && application.active && application.lancement != null,
                ) { Text(stringResource(R.string.apps_open)) }
                TextButton(onClick = { actions.onArreter(application) }, enabled = libre && application.active) {
                    Text(stringResource(R.string.apps_stop))
                }
                when {
                    !application.active -> TextButton(onClick = { actions.onReactiver(application) }, enabled = libre) {
                        Text(stringResource(R.string.apps_enable))
                    }

                    desactivable -> TextButton(onClick = { actions.onDesactiver(application) }, enabled = libre) {
                        Text(stringResource(R.string.apps_disable))
                    }
                }
                if (!application.systeme) {
                    TextButton(onClick = { actions.onDesinstaller(application) }, enabled = libre) {
                        Text(stringResource(R.string.apps_uninstall), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { actions.onChoisir(null) }) { Text(stringResource(R.string.capture_close)) } },
    )
}

@Composable
private fun ConfirmationDialogue(demande: ConfirmationApplication, actions: ActionsApplicationsUi) {
    val application = demande.application
    val desinstallation = demande is ConfirmationApplication.Desinstallation
    AlertDialog(
        onDismissRequest = actions.onAnnuler,
        icon = { IconeApplication(application, 48) },
        title = {
            Text(
                stringResource(
                    if (desinstallation) R.string.apps_confirm_uninstall_title else R.string.apps_confirm_disable_title,
                    application.nom,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(application.paquet, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        if (desinstallation) R.string.apps_confirm_uninstall_body else R.string.apps_confirm_disable_body,
                    ),
                )
                (demande as? ConfirmationApplication.Desactivation)?.entree?.effetDeBord?.let { effet ->
                    Text(stringResource(R.string.apps_side_effect, effet), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = actions.onConfirmer,
                colors = if (desinstallation) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(stringResource(if (desinstallation) R.string.apps_uninstall else R.string.apps_disable)) }
        },
        dismissButton = { TextButton(onClick = actions.onAnnuler) { Text(stringResource(R.string.confirm_cancel)) } },
    )
}
