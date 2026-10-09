package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.components.Bloc
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.QrCode

/**
 * Pairing screen: the TV shows its address as a QR code for the companion to scan, so nobody has to dig
 * up the IP address in the settings and type it.
 *
 * The three connection prerequisites are shown separately so a failure points at the missing one.
 * The QR code grants nothing: it holds only address and port, and the TV still asks to authorize
 * debugging on the remote.
 */
@Composable
fun ConnexionTvScreen(
    etat: EtatUi,
    onActualiser: () -> Unit,
) {
    val premierBouton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { premierBouton.requestFocus() }
    }
    val contact = etat.contact

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.pairing_title),
            sousTitre = stringResource(R.string.pairing_subtitle),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Bloc(modifier = Modifier.weight(1f)) {
                if (contact.joignable) {
                    QrCode(
                        contenu = contact.uri(),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        taille = 200.dp,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.pairing_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Bloc(modifier = Modifier.weight(1.4f)) {
                Text(
                    text = stringResource(R.string.pairing_address_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        contact.adresse.isBlank() -> stringResource(R.string.pairing_no_address)
                        // Port 0 means network debugging is off; showing it would not help.
                        contact.port > 0 -> "${contact.adresse}:${contact.port}"
                        else -> contact.adresse
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(14.dp))

                Condition(
                    remplie = contact.optionsDeveloppeur,
                    libelle = stringResource(R.string.pairing_check_developer),
                )
                Condition(
                    remplie = contact.debogageActive,
                    libelle = stringResource(R.string.pairing_check_adb),
                )
                Condition(
                    remplie = contact.debogageReseau,
                    libelle = stringResource(R.string.pairing_check_network),
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (contact.joignable) {
                            R.string.pairing_steps
                        } else {
                            R.string.pairing_enable_adb
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onActualiser, modifier = Modifier.focusRequester(premierBouton)) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

/** A connection prerequisite: green dot when met, red otherwise. */
@Composable
private fun Condition(remplie: Boolean, libelle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(
                    if (remplie) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    CircleShape,
                ),
        )
        Text(
            text = if (remplie) "✓  $libelle" else "✕  $libelle",
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (remplie) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
