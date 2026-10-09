package net.jolabs40.tvslim.windows.ui.ecrans

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
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_apps_24
import net.jolabs40.tvslim.windows.ressources.permissions_choose
import net.jolabs40.tvslim.windows.ressources.permissions_check
import net.jolabs40.tvslim.windows.ressources.permissions_grant
import net.jolabs40.tvslim.windows.ressources.permissions_hint
import net.jolabs40.tvslim.windows.ressources.permissions_package
import net.jolabs40.tvslim.windows.ressources.permissions_permission
import net.jolabs40.tvslim.windows.ressources.permissions_permission_hint
import net.jolabs40.tvslim.windows.ressources.permissions_revoke
import net.jolabs40.tvslim.windows.ressources.permissions_state_appop
import net.jolabs40.tvslim.windows.ressources.permissions_state_granted
import net.jolabs40.tvslim.windows.ressources.permissions_state_pending
import net.jolabs40.tvslim.windows.ressources.permissions_state_undeclared
import net.jolabs40.tvslim.windows.ressources.permissions_state_unknown
import net.jolabs40.tvslim.windows.ressources.permissions_title
import net.jolabs40.tvslim.windows.ui.ActionsPermissions
import net.jolabs40.tvslim.windows.ui.EtatApplications
import net.jolabs40.tvslim.windows.ui.EtatPermissions
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
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
    CarteSection(titre = stringResource(Res.string.permissions_title), espacement = 12.dp) {
        TexteSecondaire(stringResource(Res.string.permissions_hint))

        OutlinedTextField(
            value = etat.paquet,
            onValueChange = actions.onPaquet,
            label = { Text(stringResource(Res.string.permissions_package)) },
            trailingIcon = {
                IconButton(onClick = { choix = true }) {
                    Icon(painterResource(Res.drawable.baseline_apps_24), contentDescription = stringResource(Res.string.permissions_choose))
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = etat.permission,
            onValueChange = actions.onPermission,
            label = { Text(stringResource(Res.string.permissions_permission)) },
            placeholder = { Text(stringResource(Res.string.permissions_permission_hint)) },
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
                Text(stringResource(Res.string.permissions_check))
            }
            Button(onClick = actions.onAccorder, enabled = etat.saisieComplete && !etat.accordee) {
                Text(stringResource(Res.string.permissions_grant))
            }
            if (etat.accordee) {
                OutlinedButton(onClick = actions.onRetirer) {
                    Text(stringResource(Res.string.permissions_revoke))
                }
            }
            if (etat.lecture) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        }
    }
}

/** The TV's answer for the package and permission, in one line. */
@Composable
private fun EtatLu(etat: EtatPermissions) {
    if (!etat.aJour) return
    // An app picked from the list is read before any permission is entered: nothing to say yet.
    if (etat.permission.isBlank() && !etat.paquetIntrouvable) return

    val (texte, couleur) = when {
        etat.paquetIntrouvable ->
            stringResource(Res.string.permissions_state_unknown) to MaterialTheme.colorScheme.error

        etat.accordee ->
            stringResource(Res.string.permissions_state_granted) to MaterialTheme.colorScheme.primary

        etat.declaree ->
            stringResource(Res.string.permissions_state_pending) to MaterialTheme.colorScheme.onSurfaceVariant

        else ->
            stringResource(Res.string.permissions_state_undeclared) to MaterialTheme.colorScheme.error
    }
    Text(text = texte, style = MaterialTheme.typography.bodyMedium, color = couleur)

    // Show the app-op too, when there is one: it explains a successful `pm grant` that changes nothing.
    if (etat.appOp.isNotEmpty() && etat.modeAppOp.isNotEmpty()) {
        TexteSecondaire(
            stringResource(Res.string.permissions_state_appop, etat.appOp, etat.modeAppOp),
            petit = true,
        )
    }
}
