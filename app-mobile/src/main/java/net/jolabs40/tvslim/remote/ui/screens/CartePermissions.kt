package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ActionsPermissions
import net.jolabs40.tvslim.remote.ui.EtatPermissions
import net.jolabs40.tvslim.remote.ui.PERMISSIONS_COURANTES

/**
 * Accorde à une application du téléviseur une permission qu'Android réserve à une session ADB.
 *
 * L'état lu est affiché avant toute action : savoir qu'une permission est déjà accordée, ou
 * qu'elle n'est même pas demandée au manifeste, évite d'envoyer une commande pour rien.
 */
@Composable
fun CartePermissions(etat: EtatPermissions, actions: ActionsPermissions) {
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
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Raccourcis : les permissions qu'on vient réellement chercher ici. Le champ reste
            // libre en dessous — c'est le téléviseur qui tranche pour tout le reste.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                label = { Text(stringResource(R.string.permissions_permission)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            EtatLu(etat)

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

/** Ce que le téléviseur a répondu sur le couple paquet/permission, en une ligne. */
@Composable
private fun EtatLu(etat: EtatPermissions) {
    if (!etat.aJour) return

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

    // Le second verrou, quand la permission en a un : l'afficher évite de chercher pourquoi un
    // `pm grant` réussi ne change rien.
    if (etat.appOp.isNotEmpty() && etat.modeAppOp.isNotEmpty()) {
        Text(
            text = stringResource(R.string.permissions_state_appop, etat.appOp, etat.modeAppOp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
