package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.soutien.InvitationSoutien

/**
 * Le bandeau de soutien, en tête de chaque onglet : il se montre après un service rendu, et seulement quand
 * `PiloteSoutien` le décide. Il ne bloque rien, et chacun de ses trois boutons le referme.
 */
@Composable
fun BanniereSoutien(
    visible: Boolean,
    onSoutenir: () -> Unit,
    onDejaFait: () -> Unit,
    onPlusTard: () -> Unit,
) {
    if (!visible) return
    val liens = LocalUriHandler.current
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Favorite, contentDescription = null)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.support_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(text = stringResource(R.string.support_text), style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                onClick = {
                    // Sans navigateur, rien ne casse : le bandeau s'efface comme pour « Plus tard ».
                    runCatching { liens.openUri(InvitationSoutien.LIEN) }
                    onSoutenir()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.support_donate))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDejaFait) { Text(stringResource(R.string.support_already)) }
                TextButton(onClick = onPlusTard) { Text(stringResource(R.string.support_later)) }
            }
        }
    }
}

/** Le lien permanent, discret, en bas de l'onglet Téléviseur : le compagnon n'a pas d'écran « À propos ». */
@Composable
fun LienSoutien() {
    val liens = LocalUriHandler.current
    TextButton(
        onClick = { runCatching { liens.openUri(InvitationSoutien.LIEN) } },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.support_link))
    }
}
