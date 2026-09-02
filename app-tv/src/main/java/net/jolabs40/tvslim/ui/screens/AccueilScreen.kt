package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.components.Bandeau
import net.jolabs40.tvslim.ui.components.Bloc
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.LigneMesure

@Composable
fun AccueilScreen(
    etat: EtatUi,
    onReglages: () -> Unit,
    onAppairage: () -> Unit,
    onActualiser: () -> Unit,
    onFermerMessage: () -> Unit,
) {
    val premierBouton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { premierBouton.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.app_name),
            sousTitre = stringResource(R.string.home_subtitle),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Bloc(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.device_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                LigneMesure(
                    stringResource(R.string.device_model),
                    "${etat.infos.marque} ${etat.infos.modele}",
                )
                LigneMesure(stringResource(R.string.device_android), etat.infos.versionAndroid)
                LigneMesure(
                    stringResource(R.string.device_memory),
                    stringResource(
                        R.string.device_memory_value,
                        etat.infos.memoireLibreMo,
                        etat.infos.memoireTotaleMo,
                    ),
                )
                LigneMesure(
                    stringResource(R.string.device_packages_active),
                    etat.infos.paquetsInstalles.toString(),
                )
                LigneMesure(
                    stringResource(R.string.device_packages_disabled),
                    etat.infos.paquetsDesactives.toString(),
                )
                LigneMesure(
                    stringResource(R.string.device_home),
                    etat.infos.accueilActuel.ifBlank { "—" },
                )
            }

            Bloc(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.companion_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.companion_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        if (etat.gardienActif) {
                            R.string.watchdog_on
                        } else {
                            R.string.watchdog_off
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (etat.gardienActif) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAppairage, modifier = Modifier.focusRequester(premierBouton)) {
                Text(stringResource(R.string.action_pairing))
            }
            Button(onClick = onReglages) { Text(stringResource(R.string.action_settings)) }
            Button(onClick = onActualiser) { Text(stringResource(R.string.action_refresh)) }
        }

        etat.message?.let { message ->
            Spacer(Modifier.height(16.dp))
            Bandeau(message = message, onFermer = onFermerMessage)
        }

        if (etat.chargement) {
            Text(
                text = stringResource(R.string.common_working),
                modifier = Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
