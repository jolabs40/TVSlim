package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.remote.ui.EtatRemote

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnexionScreen(
    etat: EtatRemote,
    onHote: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnecter: () -> Unit,
    onDeconnecter: () -> Unit,
    onActualiser: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.connection_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.connection_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = etat.hoteSaisi,
                onValueChange = onHote,
                label = { Text(stringResource(R.string.connection_host)) },
                singleLine = true,
                enabled = !etat.connecte,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = etat.portSaisi,
                onValueChange = onPort,
                label = { Text(stringResource(R.string.connection_port)) },
                singleLine = true,
                enabled = !etat.connecte,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (etat.connecte) {
                OutlinedButton(onClick = onDeconnecter) {
                    Text(stringResource(R.string.connection_disconnect))
                }
                Button(onClick = onActualiser) { Text(stringResource(R.string.action_refresh)) }
            } else {
                Button(
                    onClick = onConnecter,
                    enabled = etat.connexion.etat != EtatConnexion.CONNEXION,
                ) {
                    Text(stringResource(R.string.connection_connect))
                }
            }
            if (etat.connexion.etat == EtatConnexion.CONNEXION || etat.chargement) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (etat.connexion.message.isNotBlank()) {
            Text(
                text = etat.connexion.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (etat.connecte) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.device_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Mesure(
                        stringResource(R.string.device_model),
                        "${etat.infos.marque} ${etat.infos.modele}",
                    )
                    Mesure(stringResource(R.string.device_android), etat.infos.versionAndroid)
                    Mesure(
                        stringResource(R.string.device_memory),
                        stringResource(
                            R.string.device_memory_value,
                            etat.infos.memoireLibreMo,
                            etat.infos.memoireTotaleMo,
                        ),
                    )
                    Mesure(
                        stringResource(R.string.device_packages_active),
                        etat.infos.paquetsInstalles.toString(),
                    )
                    Mesure(
                        stringResource(R.string.device_packages_disabled),
                        etat.infos.paquetsDesactives.toString(),
                    )
                    Mesure(
                        stringResource(R.string.device_home),
                        etat.infos.accueilActuel.ifBlank { "—" },
                    )
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.connection_help_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.connection_help_1),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.connection_help_2),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.connection_help_3),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Mesure(libelle: String, valeur: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = libelle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = valeur, style = MaterialTheme.typography.bodyMedium)
    }
}
