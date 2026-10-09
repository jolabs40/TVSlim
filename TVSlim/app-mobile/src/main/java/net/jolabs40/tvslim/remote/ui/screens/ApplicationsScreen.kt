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
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ApplyConfirmation
import net.jolabs40.tvslim.remote.ui.ApplicationsState

class ApplicationsUiActions(
    val onLoad: () -> Unit,
    val onSearchChange: (String) -> Unit,
    val onChoose: (DeviceApplication?) -> Unit,
    val onOpen: (DeviceApplication) -> Unit,
    val onStop: (DeviceApplication) -> Unit,
    val onDisable: (DeviceApplication) -> Unit,
    val onEnable: (DeviceApplication) -> Unit,
    val onUninstall: (DeviceApplication) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    /** The catalogue entry that allows disabling the app, or `null` if it cannot be disabled from here. */
    val disableable: (DeviceApplication) -> PackageEntry?,
)

/** Tapping an app opens its actions. The list loads on first display; names and icons fill in as they arrive. */
@Composable
fun ApplicationsScreen(connected: Boolean, state: ApplicationsState, actions: ApplicationsUiActions) {
    if (!connected) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.apps_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LaunchedEffect(Unit) { if (!state.loaded && !state.loading) actions.onLoad() }

    state.chosen?.let { ActionsDialog(it, actions.disableable(it) != null, state.busy != null, actions) }
    state.confirmation?.let { ConfirmationDialog(it, actions) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.search,
                onValueChange = actions.onSearchChange,
                label = { Text(stringResource(R.string.apps_search)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.onLoad, enabled = !state.loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }
        Column(modifier = Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.apps_count, state.applications.size, state.disabledCount),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.loading) {
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.apps_reading_list), style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(
                        progress = { progress.first.toFloat() / progress.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.apps_reading, progress.first, progress.second), style = MaterialTheme.typography.bodySmall)
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
            items(state.shown, key = { it.packageName }) { application ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onChoose(application) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ApplicationIcon(application, 40)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Badges(application)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun Badges(application: DeviceApplication) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (application.system) R.string.apps_system else R.string.apps_installed),
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

/** The icon read from the device, or the Android robot until it arrives. Also used by the permissions app picker. */
@Composable
internal fun ApplicationIcon(application: DeviceApplication, size: Int) {
    val image: ImageBitmap? = remember(application.packageName, application.icon) {
        application.icon?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
    }
    Box(modifier = Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.size(size.dp))
        } else {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size((size * 0.7).dp),
            )
        }
    }
}

@Composable
private fun ActionsDialog(
    application: DeviceApplication,
    disableable: Boolean,
    busy: Boolean,
    actions: ApplicationsUiActions,
) {
    AlertDialog(
        onDismissRequest = { actions.onChoose(null) },
        icon = { ApplicationIcon(application, 48) },
        title = { Text(application.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(application.packageName, style = MaterialTheme.typography.bodySmall)
                Badges(application)
                val free = !busy
                TextButton(
                    onClick = { actions.onOpen(application) },
                    enabled = free && application.active && application.launch != null,
                ) { Text(stringResource(R.string.apps_open)) }
                TextButton(onClick = { actions.onStop(application) }, enabled = free && application.active) {
                    Text(stringResource(R.string.apps_stop))
                }
                when {
                    !application.active -> TextButton(onClick = { actions.onEnable(application) }, enabled = free) {
                        Text(stringResource(R.string.apps_enable))
                    }

                    disableable -> TextButton(onClick = { actions.onDisable(application) }, enabled = free) {
                        Text(stringResource(R.string.apps_disable))
                    }
                }
                if (!application.system) {
                    TextButton(onClick = { actions.onUninstall(application) }, enabled = free) {
                        Text(stringResource(R.string.apps_uninstall), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { actions.onChoose(null) }) { Text(stringResource(R.string.capture_close)) } },
    )
}

@Composable
private fun ConfirmationDialog(request: ApplyConfirmation, actions: ApplicationsUiActions) {
    val application = request.application
    val isUninstall = request is ApplyConfirmation.Uninstallation
    AlertDialog(
        onDismissRequest = actions.onCancel,
        icon = { ApplicationIcon(application, 48) },
        title = {
            Text(
                stringResource(
                    if (isUninstall) R.string.apps_confirm_uninstall_title else R.string.apps_confirm_disable_title,
                    application.name,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(application.packageName, style = MaterialTheme.typography.bodySmall)
                Text(
                    stringResource(
                        if (isUninstall) R.string.apps_confirm_uninstall_body else R.string.apps_confirm_disable_body,
                    ),
                )
                (request as? ApplyConfirmation.Disabling)?.entry?.sideEffect?.let { effect ->
                    Text(stringResource(R.string.apps_side_effect, effect), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = actions.onConfirm,
                colors = if (isUninstall) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(stringResource(if (isUninstall) R.string.apps_uninstall else R.string.apps_disable)) }
        },
        dismissButton = { TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.confirm_cancel)) } },
    )
}
