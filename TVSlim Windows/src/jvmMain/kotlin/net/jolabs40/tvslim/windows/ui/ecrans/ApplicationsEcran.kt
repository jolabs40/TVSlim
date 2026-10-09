package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.applications.ApplicationAppareil
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.action_refresh
import net.jolabs40.tvslim.windows.ressources.apps_confirm_disable_body
import net.jolabs40.tvslim.windows.ressources.apps_confirm_disable_title
import net.jolabs40.tvslim.windows.ressources.apps_confirm_uninstall_body
import net.jolabs40.tvslim.windows.ressources.apps_confirm_uninstall_title
import net.jolabs40.tvslim.windows.ressources.apps_count
import net.jolabs40.tvslim.windows.ressources.apps_disable
import net.jolabs40.tvslim.windows.ressources.apps_disabled
import net.jolabs40.tvslim.windows.ressources.apps_enable
import net.jolabs40.tvslim.windows.ressources.apps_helper_note
import net.jolabs40.tvslim.windows.ressources.apps_installed
import net.jolabs40.tvslim.windows.ressources.apps_not_connected
import net.jolabs40.tvslim.windows.ressources.apps_open
import net.jolabs40.tvslim.windows.ressources.apps_reading
import net.jolabs40.tvslim.windows.ressources.apps_reading_list
import net.jolabs40.tvslim.windows.ressources.apps_search
import net.jolabs40.tvslim.windows.ressources.apps_side_effect
import net.jolabs40.tvslim.windows.ressources.apps_stop
import net.jolabs40.tvslim.windows.ressources.apps_system
import net.jolabs40.tvslim.windows.ressources.apps_uninstall
import net.jolabs40.tvslim.windows.ressources.baseline_android_24
import net.jolabs40.tvslim.windows.ressources.baseline_refresh_24
import net.jolabs40.tvslim.windows.ressources.baseline_search_24
import net.jolabs40.tvslim.windows.ressources.confirm_cancel
import net.jolabs40.tvslim.windows.ui.ConfirmationApplication
import net.jolabs40.tvslim.windows.ui.EtatApplications
import net.jolabs40.tvslim.windows.ui.composants.EcranVide
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image as ImageSkia

class ActionsApplicationsUi(
    val onCharger: () -> Unit,
    val onRecherche: (String) -> Unit,
    val onOuvrir: (ApplicationAppareil) -> Unit,
    val onArreter: (ApplicationAppareil) -> Unit,
    val onDesactiver: (ApplicationAppareil) -> Unit,
    val onReactiver: (ApplicationAppareil) -> Unit,
    val onDesinstaller: (ApplicationAppareil) -> Unit,
    val onConfirmer: () -> Unit,
    val onAnnuler: () -> Unit,
    /** Catalogue entry that allows disabling an app; `null` if it cannot be disabled from here. */
    val desactivable: (ApplicationAppareil) -> EntreePaquet?,
)

/**
 * Applications tab: launcher apps and user-installed apps, with icon, name and four actions. The first read
 * starts by itself; names and icons fill in as they arrive.
 */
@Composable
fun ApplicationsEcran(connecte: Boolean, etat: EtatApplications, actions: ActionsApplicationsUi) {
    if (!connecte) {
        EcranVide(stringResource(Res.string.apps_not_connected))
        return
    }
    LaunchedEffect(Unit) { if (!etat.lue && !etat.chargement) actions.onCharger() }

    etat.confirmation?.let { ConfirmationApplicationDialogue(it, actions.onConfirmer, actions.onAnnuler) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = etat.recherche,
                    onValueChange = actions.onRecherche,
                    placeholder = { Text(stringResource(Res.string.apps_search)) },
                    leadingIcon = { Icon(painterResource(Res.drawable.baseline_search_24), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).widthIn(max = 480.dp),
                )
                Text(
                    text = stringResource(Res.string.apps_count, etat.applications.size, etat.nombreDesactivees),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(0.01f))
                OutlinedButton(onClick = actions.onCharger, enabled = !etat.chargement) {
                    Icon(painterResource(Res.drawable.baseline_refresh_24), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.action_refresh))
                }
            }
            if (etat.chargement) {
                val avancee = etat.avancee
                if (avancee == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    TexteSecondaire(stringResource(Res.string.apps_reading_list), petit = true)
                } else {
                    LinearProgressIndicator(
                        progress = { avancee.first.toFloat() / avancee.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TexteSecondaire(stringResource(Res.string.apps_reading, avancee.first, avancee.second), petit = true)
                }
            }
            TexteSecondaire(stringResource(Res.string.apps_helper_note), petit = true)
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(etat.affichees, key = { it.paquet }) { application ->
                LigneApplication(
                    application = application,
                    desactivable = actions.desactivable(application) != null,
                    occupee = etat.occupee != null,
                    actions = actions,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LigneApplication(
    application: ApplicationAppareil,
    desactivable: Boolean,
    occupee: Boolean,
    actions: ActionsApplicationsUi,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconeApplication(application, taille = 40)
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = application.nom,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(if (application.systeme) Res.string.apps_system else Res.string.apps_installed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!application.active) {
                    Text(
                        text = stringResource(Res.string.apps_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TexteSecondaire(application.paquet, petit = true)
        }
        val libre = !occupee
        TextButton(onClick = { actions.onOuvrir(application) }, enabled = libre && application.active && application.lancement != null) {
            Text(stringResource(Res.string.apps_open))
        }
        TextButton(onClick = { actions.onArreter(application) }, enabled = libre && application.active) {
            Text(stringResource(Res.string.apps_stop))
        }
        when {
            !application.active -> TextButton(onClick = { actions.onReactiver(application) }, enabled = libre) {
                Text(stringResource(Res.string.apps_enable))
            }

            desactivable -> TextButton(onClick = { actions.onDesactiver(application) }, enabled = libre) {
                Text(stringResource(Res.string.apps_disable))
            }
        }
        if (!application.systeme) {
            TextButton(onClick = { actions.onDesinstaller(application) }, enabled = libre) {
                Text(stringResource(Res.string.apps_uninstall), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Icon read from the device, or the Android robot until it arrives. */
@Composable
internal fun IconeApplication(application: ApplicationAppareil, taille: Int) {
    val image: ImageBitmap? = remember(application.paquet, application.icone) {
        application.icone?.let { runCatching { ImageSkia.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }
    Box(modifier = Modifier.size(taille.dp), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.size(taille.dp))
        } else {
            Icon(
                painter = painterResource(Res.drawable.baseline_android_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size((taille * 0.7).dp),
            )
        }
    }
}

@Composable
private fun ConfirmationApplicationDialogue(
    demande: ConfirmationApplication,
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    val application = demande.application
    val desinstallation = demande is ConfirmationApplication.Desinstallation
    AlertDialog(
        onDismissRequest = onAnnuler,
        icon = { IconeApplication(application, taille = 48) },
        title = {
            Text(
                stringResource(
                    if (desinstallation) Res.string.apps_confirm_uninstall_title else Res.string.apps_confirm_disable_title,
                    application.nom,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TexteSecondaire(application.paquet, petit = true)
                Text(
                    stringResource(
                        if (desinstallation) Res.string.apps_confirm_uninstall_body else Res.string.apps_confirm_disable_body,
                    ),
                )
                (demande as? ConfirmationApplication.Desactivation)?.entree?.effetDeBord?.let { effet ->
                    Text(
                        text = stringResource(Res.string.apps_side_effect, effet),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmer,
                colors = if (desinstallation) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) {
                Text(stringResource(if (desinstallation) Res.string.apps_uninstall else Res.string.apps_disable))
            }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.confirm_cancel)) } },
    )
}
