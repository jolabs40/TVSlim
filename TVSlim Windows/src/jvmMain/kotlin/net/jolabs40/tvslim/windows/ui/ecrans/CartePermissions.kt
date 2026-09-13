package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.permissions_check
import net.jolabs40.tvslim.windows.ressources.permissions_grant
import net.jolabs40.tvslim.windows.ressources.permissions_hint
import net.jolabs40.tvslim.windows.ressources.permissions_package
import net.jolabs40.tvslim.windows.ressources.permissions_permission
import net.jolabs40.tvslim.windows.ressources.permissions_revoke
import net.jolabs40.tvslim.windows.ressources.permissions_state_appop
import net.jolabs40.tvslim.windows.ressources.permissions_state_granted
import net.jolabs40.tvslim.windows.ressources.permissions_state_pending
import net.jolabs40.tvslim.windows.ressources.permissions_state_undeclared
import net.jolabs40.tvslim.windows.ressources.permissions_state_unknown
import net.jolabs40.tvslim.windows.ressources.permissions_title
import net.jolabs40.tvslim.windows.ui.ActionsPermissions
import net.jolabs40.tvslim.windows.ui.EtatPermissions
import net.jolabs40.tvslim.windows.ui.PERMISSIONS_COURANTES
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource

/**
 * Accorde à une application du téléviseur une permission qu'Android réserve à une session ADB.
 *
 * L'état lu est affiché avant toute action : savoir qu'une permission est déjà accordée, ou qu'elle
 * n'est même pas demandée au manifeste, évite d'envoyer une commande pour rien.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CartePermissions(etat: EtatPermissions, actions: ActionsPermissions) {
    CarteSection(titre = stringResource(Res.string.permissions_title), espacement = 12.dp) {
        TexteSecondaire(stringResource(Res.string.permissions_hint))

        OutlinedTextField(
            value = etat.paquet,
            onValueChange = actions.onPaquet,
            label = { Text(stringResource(Res.string.permissions_package)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Raccourcis : les permissions qu'on vient réellement chercher ici. Le champ reste libre en
        // dessous — c'est le téléviseur qui tranche pour tout le reste.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PERMISSIONS_COURANTES.forEach { permission ->
                FilterChip(
                    selected = etat.permission == permission,
                    onClick = { actions.onPermission(permission) },
                    label = { Text(permission.substringAfterLast('.')) },
                )
            }
        }

        OutlinedTextField(
            value = etat.permission,
            onValueChange = actions.onPermission,
            label = { Text(stringResource(Res.string.permissions_permission)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        EtatLu(etat)

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

/** Ce que le téléviseur a répondu sur le couple paquet/permission, en une ligne. */
@Composable
private fun EtatLu(etat: EtatPermissions) {
    if (!etat.aJour) return

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

    // Le second verrou, quand la permission en a un : l'afficher évite de chercher pourquoi un
    // `pm grant` réussi ne change rien.
    if (etat.appOp.isNotEmpty() && etat.modeAppOp.isNotEmpty()) {
        TexteSecondaire(
            stringResource(Res.string.permissions_state_appop, etat.appOp, etat.modeAppOp),
            petit = true,
        )
    }
}
