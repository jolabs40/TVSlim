package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_apps_24
import net.jolabs40.tvslim.windows.resources.permissions_choose
import net.jolabs40.tvslim.windows.resources.permissions_check
import net.jolabs40.tvslim.windows.resources.permissions_grant
import net.jolabs40.tvslim.windows.resources.permissions_hint
import net.jolabs40.tvslim.windows.resources.permissions_package
import net.jolabs40.tvslim.windows.resources.permissions_permission
import net.jolabs40.tvslim.windows.resources.permissions_permission_hint
import net.jolabs40.tvslim.windows.resources.permissions_revoke
import net.jolabs40.tvslim.windows.resources.permissions_state_appop
import net.jolabs40.tvslim.windows.resources.permissions_state_granted
import net.jolabs40.tvslim.windows.resources.permissions_state_pending
import net.jolabs40.tvslim.windows.resources.permissions_state_undeclared
import net.jolabs40.tvslim.windows.resources.permissions_state_unknown
import net.jolabs40.tvslim.windows.resources.permissions_title
import net.jolabs40.tvslim.windows.ui.PermissionsActions
import net.jolabs40.tvslim.windows.ui.ApplicationsState
import net.jolabs40.tvslim.windows.ui.PermissionsState
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Grants a TV app a permission that Android reserves for ADB sessions.
 *
 * The current state is shown before any action, so no command is sent for a permission that is already
 * granted or not even declared in the manifest.
 *
 * Two fields rather than a free command line, because the engine validates each (an identifier, and a
 * permission the app declares) before anything is sent. The package is typed or picked from the TV's apps
 * (name first, then package); once read, the app's declared permissions are listed and a click fills the
 * Permission field. No generic shortcut chips: only the app's own list is shown.
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
    SectionCard(title = stringResource(Res.string.permissions_title), spacing = 12.dp) {
        SecondaryText(stringResource(Res.string.permissions_hint))

        OutlinedTextField(
            value = state.packageName,
            onValueChange = actions.onPackage,
            label = { Text(stringResource(Res.string.permissions_package)) },
            trailingIcon = {
                IconButton(onClick = { choice = true }) {
                    Icon(painterResource(Res.drawable.baseline_apps_24), contentDescription = stringResource(Res.string.permissions_choose))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.permission,
            onValueChange = actions.onPermission,
            label = { Text(stringResource(Res.string.permissions_permission)) },
            placeholder = { Text(stringResource(Res.string.permissions_permission_hint)) },
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
                Text(stringResource(Res.string.permissions_check))
            }
            Button(onClick = actions.onGrant, enabled = state.inputComplete && !state.isGranted) {
                Text(stringResource(Res.string.permissions_grant))
            }
            if (state.isGranted) {
                OutlinedButton(onClick = actions.onRevoke) {
                    Text(stringResource(Res.string.permissions_revoke))
                }
            }
            if (state.reading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        }
    }
}

/** The TV's answer for the package and permission, in one line. */
@Composable
private fun ReadState(state: PermissionsState) {
    if (!state.upToDate) return
    // An app picked from the list is read before any permission is entered: nothing to say yet.
    if (state.permission.isBlank() && !state.packageNotFound) return

    val (text, color) = when {
        state.packageNotFound ->
            stringResource(Res.string.permissions_state_unknown) to MaterialTheme.colorScheme.error

        state.isGranted ->
            stringResource(Res.string.permissions_state_granted) to MaterialTheme.colorScheme.primary

        state.isDeclared ->
            stringResource(Res.string.permissions_state_pending) to MaterialTheme.colorScheme.onSurfaceVariant

        else ->
            stringResource(Res.string.permissions_state_undeclared) to MaterialTheme.colorScheme.error
    }
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)

    // Show the app-op too, when there is one: it explains a successful `pm grant` that changes nothing.
    if (state.appOp.isNotEmpty() && state.appOpMode.isNotEmpty()) {
        SecondaryText(
            stringResource(Res.string.permissions_state_appop, state.appOp, state.appOpMode),
            small = true,
        )
    }
}
