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
import net.jolabs40.tvslim.remote.ui.ActionsPermissions
import net.jolabs40.tvslim.remote.ui.EtatApplications
import net.jolabs40.tvslim.remote.ui.EtatPermissions

/**
 * Grants a TV app a permission that Android reserves for an ADB session.
 *
 * The current state is shown before any action, so a permission already granted, or not even declared in the
 * manifest, is not sent for nothing. The package is typed or picked from the TV's apps; once read, its declared
 * permissions are listed and a tap fills the Permission field. No generic shortcut chips, only the app's own list.
 */
@Composable
fun CartePermissions(etat: EtatPermissions, applications: EtatApplications, actions: ActionsPermissions) {
    var choix by remember { mutableStateOf(false) }
    if (choix) {
        ChoixApplicationDialogue(
            applications = applications,
            onCharger = actions.onChargerApplications,
            onChoisir = { paquet ->
                choix = false
                actions.onChoisirPaquet(paquet)
            },
            onFermer = { choix = false },
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
                value = etat.paquet,
                onValueChange = actions.onPaquet,
                label = { Text(stringResource(R.string.permissions_package)) },
                trailingIcon = {
                    IconButton(onClick = { choix = true }) {
                        Icon(Icons.Filled.Apps, contentDescription = stringResource(R.string.permissions_choose))
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = etat.permission,
                onValueChange = actions.onPermission,
                label = { Text(stringResource(R.string.permissions_permission)) },
                placeholder = { Text(stringResource(R.string.permissions_permission_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            EtatLu(etat)
            etat.lues?.takeIf { etat.aJour && it.paquetTrouve }?.let { lues ->
                PermissionsDeclarees(lues = lues, onChoisir = actions.onPermission)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = actions.onLire, enabled = etat.paquet.isNotBlank()) {
                    Text(stringResource(R.string.permissions_check))
                }
                Button(
                    onClick = actions.onAccorder,
                    enabled = etat.saisieComplete && !etat.accordee,
                ) {
                    Text(stringResource(R.string.permissions_grant))
                }
                if (etat.accordee) {
                    OutlinedButton(onClick = actions.onRetirer) {
                        Text(stringResource(R.string.permissions_revoke))
                    }
                }
                if (etat.lecture) CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun EtatLu(etat: EtatPermissions) {
    if (!etat.aJour) return
    // An app picked from the list is read before any permission is typed: nothing to report yet.
    if (etat.permission.isBlank() && !etat.paquetIntrouvable) return

    val (texte, couleur) = when {
        etat.paquetIntrouvable ->
            stringResource(R.string.permissions_state_unknown) to MaterialTheme.colorScheme.error

        etat.accordee ->
            stringResource(R.string.permissions_state_granted) to
                MaterialTheme.colorScheme.primary

        etat.declaree ->
            stringResource(R.string.permissions_state_pending) to
                MaterialTheme.colorScheme.onSurfaceVariant

        else ->
            stringResource(R.string.permissions_state_undeclared) to
                MaterialTheme.colorScheme.error
    }

    Text(text = texte, style = MaterialTheme.typography.bodyMedium, color = couleur)

    // The app-op is a second lock: it explains why a successful `pm grant` may change nothing.
    if (etat.appOp.isNotEmpty() && etat.modeAppOp.isNotEmpty()) {
        Text(
            text = stringResource(R.string.permissions_state_appop, etat.appOp, etat.modeAppOp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
