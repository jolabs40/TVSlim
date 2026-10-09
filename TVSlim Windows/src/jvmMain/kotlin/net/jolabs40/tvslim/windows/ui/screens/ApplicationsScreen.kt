package net.jolabs40.tvslim.windows.ui.screens

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
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.action_refresh
import net.jolabs40.tvslim.windows.resources.apps_confirm_disable_body
import net.jolabs40.tvslim.windows.resources.apps_confirm_disable_title
import net.jolabs40.tvslim.windows.resources.apps_confirm_uninstall_body
import net.jolabs40.tvslim.windows.resources.apps_confirm_uninstall_title
import net.jolabs40.tvslim.windows.resources.apps_count
import net.jolabs40.tvslim.windows.resources.apps_disable
import net.jolabs40.tvslim.windows.resources.apps_disabled
import net.jolabs40.tvslim.windows.resources.apps_enable
import net.jolabs40.tvslim.windows.resources.apps_helper_note
import net.jolabs40.tvslim.windows.resources.apps_installed
import net.jolabs40.tvslim.windows.resources.apps_not_connected
import net.jolabs40.tvslim.windows.resources.apps_open
import net.jolabs40.tvslim.windows.resources.apps_reading
import net.jolabs40.tvslim.windows.resources.apps_reading_list
import net.jolabs40.tvslim.windows.resources.apps_search
import net.jolabs40.tvslim.windows.resources.apps_side_effect
import net.jolabs40.tvslim.windows.resources.apps_stop
import net.jolabs40.tvslim.windows.resources.apps_system
import net.jolabs40.tvslim.windows.resources.apps_uninstall
import net.jolabs40.tvslim.windows.resources.baseline_android_24
import net.jolabs40.tvslim.windows.resources.baseline_refresh_24
import net.jolabs40.tvslim.windows.resources.baseline_search_24
import net.jolabs40.tvslim.windows.resources.confirm_cancel
import net.jolabs40.tvslim.windows.ui.ApplyConfirmation
import net.jolabs40.tvslim.windows.ui.ApplicationsState
import net.jolabs40.tvslim.windows.ui.components.EmptyScreen
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.skia.Image as ImageSkia

class ApplicationsUiActions(
    val onLoad: () -> Unit,
    val onSearchChange: (String) -> Unit,
    val onOpen: (DeviceApplication) -> Unit,
    val onStop: (DeviceApplication) -> Unit,
    val onDisable: (DeviceApplication) -> Unit,
    val onEnable: (DeviceApplication) -> Unit,
    val onUninstall: (DeviceApplication) -> Unit,
    val onConfirm: () -> Unit,
    val onCancel: () -> Unit,
    /** Catalogue entry that allows disabling an app; `null` if it cannot be disabled from here. */
    val disableable: (DeviceApplication) -> PackageEntry?,
)

/**
 * Applications tab: launcher apps and user-installed apps, with icon, name and four actions. The first read
 * starts by itself; names and icons fill in as they arrive.
 */
@Composable
fun ApplicationsScreen(connected: Boolean, state: ApplicationsState, actions: ApplicationsUiActions) {
    if (!connected) {
        EmptyScreen(stringResource(Res.string.apps_not_connected))
        return
    }
    LaunchedEffect(Unit) { if (!state.loaded && !state.loading) actions.onLoad() }

    state.confirmation?.let { ApplicationConfirmationDialog(it, actions.onConfirm, actions.onCancel) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.search,
                    onValueChange = actions.onSearchChange,
                    placeholder = { Text(stringResource(Res.string.apps_search)) },
                    leadingIcon = { Icon(painterResource(Res.drawable.baseline_search_24), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).widthIn(max = 480.dp),
                )
                Text(
                    text = stringResource(Res.string.apps_count, state.applications.size, state.disabledCount),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(0.01f))
                OutlinedButton(onClick = actions.onLoad, enabled = !state.loading) {
                    Icon(painterResource(Res.drawable.baseline_refresh_24), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.action_refresh))
                }
            }
            if (state.loading) {
                val progress = state.progress
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SecondaryText(stringResource(Res.string.apps_reading_list), small = true)
                } else {
                    LinearProgressIndicator(
                        progress = { progress.first.toFloat() / progress.second.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SecondaryText(stringResource(Res.string.apps_reading, progress.first, progress.second), small = true)
                }
            }
            SecondaryText(stringResource(Res.string.apps_helper_note), small = true)
        }
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.shown, key = { it.packageName }) { application ->
                ApplicationRow(
                    application = application,
                    disableable = actions.disableable(application) != null,
                    busy = state.busy != null,
                    actions = actions,
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ApplicationRow(
    application: DeviceApplication,
    disableable: Boolean,
    busy: Boolean,
    actions: ApplicationsUiActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ApplicationIcon(application, size = 40)
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = application.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(if (application.system) Res.string.apps_system else Res.string.apps_installed),
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
            SecondaryText(application.packageName, small = true)
        }
        val free = !busy
        TextButton(onClick = { actions.onOpen(application) }, enabled = free && application.active && application.launch != null) {
            Text(stringResource(Res.string.apps_open))
        }
        TextButton(onClick = { actions.onStop(application) }, enabled = free && application.active) {
            Text(stringResource(Res.string.apps_stop))
        }
        when {
            !application.active -> TextButton(onClick = { actions.onEnable(application) }, enabled = free) {
                Text(stringResource(Res.string.apps_enable))
            }

            disableable -> TextButton(onClick = { actions.onDisable(application) }, enabled = free) {
                Text(stringResource(Res.string.apps_disable))
            }
        }
        if (!application.system) {
            TextButton(onClick = { actions.onUninstall(application) }, enabled = free) {
                Text(stringResource(Res.string.apps_uninstall), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Icon read from the device, or the Android robot until it arrives. */
@Composable
internal fun ApplicationIcon(application: DeviceApplication, size: Int) {
    val image: ImageBitmap? = remember(application.packageName, application.icon) {
        application.icon?.let { runCatching { ImageSkia.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
    }
    Box(modifier = Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.size(size.dp))
        } else {
            Icon(
                painter = painterResource(Res.drawable.baseline_android_24),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size((size * 0.7).dp),
            )
        }
    }
}

@Composable
private fun ApplicationConfirmationDialog(
    request: ApplyConfirmation,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val application = request.application
    val isUninstall = request is ApplyConfirmation.Uninstallation
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { ApplicationIcon(application, size = 48) },
        title = {
            Text(
                stringResource(
                    if (isUninstall) Res.string.apps_confirm_uninstall_title else Res.string.apps_confirm_disable_title,
                    application.name,
                ),
            )
        },
        text = {
            Column(modifier = Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryText(application.packageName, small = true)
                Text(
                    stringResource(
                        if (isUninstall) Res.string.apps_confirm_uninstall_body else Res.string.apps_confirm_disable_body,
                    ),
                )
                (request as? ApplyConfirmation.Disabling)?.entry?.sideEffect?.let { effect ->
                    Text(
                        text = stringResource(Res.string.apps_side_effect, effect),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isUninstall) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) {
                Text(stringResource(if (isUninstall) Res.string.apps_uninstall else Res.string.apps_disable))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.confirm_cancel)) } },
    )
}
