package net.jolabs40.tvslim.remote.ui.screens

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.PackagePermissions
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ApplicationsState

/**
 * App picker for the permissions card. Uses the Applications tab's list (launcher apps and user-installed ones),
 * loaded on first use and cached. Any other package can still be typed by hand.
 */
@Composable
fun ApplicationChoiceDialog(
    applications: ApplicationsState,
    onLoad: () -> Unit,
    onChoose: (String) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { if (!applications.loaded && !applications.loading) onLoad() }
    var search by rememberSaveable { mutableStateOf("") }
    val list = remember(applications.applications, search) {
        applications.applications
            .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.permissions_choose_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.permissions_choose_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.permissions_choose_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (applications.loading) {
                    val progress = applications.progress
                    Text(
                        text = if (progress != null) {
                            stringResource(R.string.permissions_choose_loading, progress.first, progress.second)
                        } else {
                            stringResource(R.string.permissions_choose_loading_start)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (!applications.loading && list.isEmpty()) {
                    Text(stringResource(R.string.permissions_choose_none), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(list, key = { it.packageName }) { application ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChoose(application.packageName) }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ApplicationIcon(application, size = 36)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = application.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = application.packageName,
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
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.confirm_cancel)) } },
    )
}

/** Permissions declared in the app's manifest, ungranted first, granted ones on request. A tap fills the field. */
@Composable
fun DeclaredPermissions(fetched: PackagePermissions, onChoose: (String) -> Unit) {
    var showAll by rememberSaveable(fetched) { mutableStateOf(false) }
    val toGrant = fetched.requested.filterNot { it in fetched.granted }.sorted()
    val granted = fetched.requested.filter { it in fetched.granted }.sorted()

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.permissions_declared_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (fetched.requested.isEmpty()) {
            Text(stringResource(R.string.permissions_declared_none), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        (toGrant + if (showAll) granted else emptyList()).forEach { permission ->
            val isGranted = permission in fetched.granted
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onChoose(permission) }
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
                    text = stringResource(if (isGranted) R.string.permissions_declared_granted else R.string.permissions_declared_not_granted),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (granted.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(
                    if (showAll) {
                        stringResource(R.string.permissions_declared_hide_granted)
                    } else {
                        stringResource(R.string.permissions_declared_show_granted, granted.size)
                    },
                )
            }
        }
    }
}
