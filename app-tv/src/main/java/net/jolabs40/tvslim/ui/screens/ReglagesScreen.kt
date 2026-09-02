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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
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
    onFermerMessage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.settings_title),
            sousTitre = stringResource(R.string.settings_subtitle),
        )

        Bloc(modifier = Modifier.fillMaxWidth()) {
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

        etat.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Bandeau(message = message, onFermer = onFermerMessage)
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item(key = "gardien") {
                LigneFocusable(onClick = { onGardien(!etat.gardienActif) }) {
                    LigneBascule(
                        coche = etat.gardienActif,
                        titre = stringResource(R.string.settings_watchdog_title),
                        description = stringResource(R.string.settings_watchdog_desc),
                    )
                }
            }

            items(etat.reglages, key = { it.reglage.cle }) { ligne ->
                LigneFocusable(onClick = { onBasculerReglage(ligne) }) {
                    LigneBascule(
                        coche = ligne.optimise,
                        titre = ligne.reglage.nom,
                        description = ligne.reglage.description,
                        valeur = stringResource(
                            R.string.settings_current_value,
                            ligne.valeurActuelle ?: "—",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun LigneBascule(
    coche: Boolean,
    titre: String,
    description: String,
    valeur: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (coche) "☑" else "☐",
            modifier = Modifier.padding(end = 14.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titre,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (valeur != null) {
            Text(
                text = valeur,
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
