package net.jolabs40.tvslim.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.privileged.EtatPrivilege
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.LigneReglage
import net.jolabs40.tvslim.ui.components.Bandeau
import net.jolabs40.tvslim.ui.components.Bloc
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.LigneFocusable

@Composable
fun ReglagesScreen(
    etat: EtatUi,
    onBasculerReglage: (LigneReglage) -> Unit,
    onGardien: (Boolean) -> Unit,
    onAutoriser: () -> Unit,
    onInstallerShizuku: () -> Unit,
    onFermerMessage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.settings_title),
            sousTitre = stringResource(R.string.settings_subtitle),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Bloc(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.privileges_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(libellePrivilege(etat.privilege)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = couleurPrivilege(etat.privilege),
                )
                Text(
                    text = stringResource(consignePrivilege(etat.privilege)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (etat.privilege) {
                    EtatPrivilege.ABSENT, EtatPrivilege.SERVICE_ARRETE -> {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onInstallerShizuku) {
                            Text(stringResource(R.string.action_install_shizuku))
                        }
                    }

                    EtatPrivilege.PRET -> Unit

                    else -> {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onAutoriser) {
                            Text(stringResource(R.string.action_grant_privileges))
                        }
                    }
                }
            }

            Bloc(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_direct_write_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (etat.ecritureDirecte) {
                            R.string.settings_direct_write_yes
                        } else {
                            R.string.settings_direct_write_no
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (etat.ecritureDirecte) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_adb_grant_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        etat.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Bandeau(message = message, onFermer = onFermerMessage)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "gardien") {
                LigneFocusable(onClick = { onGardien(!etat.gardienActif) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (etat.gardienActif) "☑" else "☐",
                            modifier = Modifier.padding(end = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_watchdog_title),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(R.string.settings_watchdog_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            items(etat.reglages, key = { it.reglage.cle }) { ligne ->
                LigneFocusable(onClick = { onBasculerReglage(ligne) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (ligne.optimise) "☑" else "☐",
                            modifier = Modifier.padding(end = 14.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ligne.reglage.nom,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = ligne.reglage.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.settings_current_value,
                                ligne.valeurActuelle ?: "—",
                            ),
                            modifier = Modifier.padding(start = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
