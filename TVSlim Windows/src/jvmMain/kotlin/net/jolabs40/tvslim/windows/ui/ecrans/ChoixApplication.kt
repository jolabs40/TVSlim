package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.PermissionsPaquet
import net.jolabs40.tvslim.windows.ui.EtatApplications
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.confirm_cancel
import net.jolabs40.tvslim.windows.ressources.permissions_choose_hint
import net.jolabs40.tvslim.windows.ressources.permissions_choose_loading
import net.jolabs40.tvslim.windows.ressources.permissions_choose_loading_start
import net.jolabs40.tvslim.windows.ressources.permissions_choose_none
import net.jolabs40.tvslim.windows.ressources.permissions_choose_search
import net.jolabs40.tvslim.windows.ressources.permissions_choose_title
import net.jolabs40.tvslim.windows.ressources.permissions_declared_granted
import net.jolabs40.tvslim.windows.ressources.permissions_declared_hide_granted
import net.jolabs40.tvslim.windows.ressources.permissions_declared_none
import net.jolabs40.tvslim.windows.ressources.permissions_declared_not_granted
import net.jolabs40.tvslim.windows.ressources.permissions_declared_show_granted
import net.jolabs40.tvslim.windows.ressources.permissions_declared_title
import org.jetbrains.compose.resources.stringResource

/**
 * App picker for the permissions card: name first, then package, with icon. Uses the Applications tab's list
 * (launcher and user-installed apps), read on first use and cached. Any other package can still be typed.
 */
@Composable
fun ChoixApplicationDialogue(
    applications: EtatApplications,
    onCharger: () -> Unit,
    onChoisir: (String) -> Unit,
    onFermer: () -> Unit,
) {
    LaunchedEffect(Unit) { if (!applications.lue && !applications.chargement) onCharger() }
    var recherche by rememberSaveable { mutableStateOf("") }
    val liste = remember(applications.applications, recherche) {
        applications.applications
            .filter { recherche.isBlank() || it.nom.contains(recherche, ignoreCase = true) || it.paquet.contains(recherche, ignoreCase = true) }
            .sortedBy { it.nom.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(Res.string.permissions_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(Res.string.permissions_choose_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = recherche,
                    onValueChange = { recherche = it },
                    placeholder = { Text(stringResource(Res.string.permissions_choose_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (applications.chargement) {
                    val avancee = applications.avancee
                    Text(
                        text = if (avancee != null) {
                            stringResource(Res.string.permissions_choose_loading, avancee.first, avancee.second)
                        } else {
                            stringResource(Res.string.permissions_choose_loading_start)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!applications.chargement && liste.isEmpty()) {
                    Text(stringResource(Res.string.permissions_choose_none), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(liste, key = { it.paquet }) { application ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChoisir(application.paquet) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconeApplication(application, taille = 36)
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
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onFermer) { Text(stringResource(Res.string.confirm_cancel)) } },
    )
}

/**
 * Permissions the app requests in its manifest: those still to grant first, the others on demand. A click
 * fills the Permission field. Not a canned shortcut list: only what this app declares.
 */
@Composable
fun PermissionsDeclarees(lues: PermissionsPaquet, onChoisir: (String) -> Unit) {
    var toutes by rememberSaveable(lues) { mutableStateOf(false) }
    val aAccorder = lues.demandees.filterNot { it in lues.accordees }.sorted()
    val accordees = lues.demandees.filter { it in lues.accordees }.sorted()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(Res.string.permissions_declared_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (lues.demandees.isEmpty()) {
            Text(stringResource(Res.string.permissions_declared_none), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        (aAccorder + if (toutes) accordees else emptyList()).forEach { permission ->
            val accordee = permission in lues.accordees
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoisir(permission) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = permission.substringAfterLast('.'), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = permission,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(if (accordee) Res.string.permissions_declared_granted else Res.string.permissions_declared_not_granted),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (accordee) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (accordees.isNotEmpty()) {
            TextButton(onClick = { toutes = !toutes }) {
                Text(
                    if (toutes) {
                        stringResource(Res.string.permissions_declared_hide_granted)
                    } else {
                        stringResource(Res.string.permissions_declared_show_granted, accordees.size)
                    },
                )
            }
        }
    }
}
