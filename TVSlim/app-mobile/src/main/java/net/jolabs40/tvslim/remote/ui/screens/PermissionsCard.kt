package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.PermissionsActions
import net.jolabs40.tvslim.remote.ui.ApplicationsState
import net.jolabs40.tvslim.remote.ui.PermissionsState

/**
 * Grants a TV app a permission that Android reserves for an ADB session.
 *
 * The current state is shown before any action, so a permission already granted, or not even declared in the
 * manifest, is not sent for nothing. The package is typed or picked from the TV's apps; once read, its declared
 * permissions are listed and a tap fills the Permission field. No generic shortcut chips, only the app's own list.
 */
@Composable
fun PermissionsCard(state: PermissionsState, applications: ApplicationsState, actions: PermissionsActions) {
    var choice by remember { mutableStateOf(false) }
    if (choice) {
        ApplicationChoiceDialog(
            applications = applications,
            onLoad = actions.onLoadApps,
            onChoose = { packageName ->
                choice = false
                actions.onChoosePackage(packageName)
            },
            onClose = { choice = false },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.permissions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.packageName,
                onValueChange = actions.onPackage,
                label = { Text(stringResource(R.string.permissions_package)) },
                trailingIcon = {
                    IconButton(onClick = { choice = true }) {
                        Icon(Icons.Filled.Apps, contentDescription = stringResource(R.string.permissions_choose))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.permission,
                onValueChange = actions.onPermission,
                label = { Text(stringResource(R.string.permissions_permission)) },
                placeholder = { Text(stringResource(R.string.permissions_permission_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ReadState(state)
            state.fetched?.takeIf { state.upToDate && it.packageFound }?.let { fetched ->
                DeclaredPermissions(fetched = fetched, onChoose = actions.onPermission)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = actions.onRead, enabled = state.packageName.isNotBlank()) {
                    Text(stringResource(R.string.permissions_check))
                }
                Button(
                    onClick = actions.onGrant,
                    enabled = state.inputComplete && !state.isGranted,
                ) {
                    Text(stringResource(R.string.permissions_grant))
                }
                if (state.isGranted) {
                    OutlinedButton(onClick = actions.onRevoke) {
                        Text(stringResource(R.string.permissions_revoke))
                    }
                }
                if (state.reading) CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ReadState(state: PermissionsState) {
    if (!state.upToDate) return
    // An app picked from the list is read before any permission is typed: nothing to report yet.
    if (state.permission.isBlank() && !state.packageNotFound) return

    val (text, color) = when {
        state.packageNotFound ->
            stringResource(R.string.permissions_state_unknown) to MaterialTheme.colorScheme.error

        state.isGranted ->
            stringResource(R.string.permissions_state_granted) to
                MaterialTheme.colorScheme.primary

        state.isDeclared ->
            stringResource(R.string.permissions_state_pending) to
                MaterialTheme.colorScheme.onSurfaceVariant

        else ->
            stringResource(R.string.permissions_state_undeclared) to
                MaterialTheme.colorScheme.error
    }

    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)

    // The app-op is a second lock: it explains why a successful `pm grant` may change nothing.
    if (state.appOp.isNotEmpty() && state.appOpMode.isNotEmpty()) {
        Text(
            text = stringResource(R.string.permissions_state_appop, state.appOp, state.appOpMode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
