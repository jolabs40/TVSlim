package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.remote.R

/**
 * Le téléviseur a défait seul une partie de ce que TV Slim avait réglé — cf. `planDeDerive`.
 *
 * Elle ne dit que l'essentiel, et un seul bouton : le détail paquet par paquet, effets de bord compris,
 * vient dans la confirmation, la même que pour une configuration réinjectée.
 */
@Composable
fun CarteDerive(plan: PlanReinjection, onReprendre: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.drift_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(R.string.drift_body), style = MaterialTheme.typography.bodyMedium)
            if (plan.aDesactiver.isNotEmpty()) {
                Text(
                    text = pluralStringResource(
                        R.plurals.drift_packages,
                        plan.aDesactiver.size,
                        plan.aDesactiver.size,
                        resume(plan.aDesactiver.map { it.nom }),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            plan.accueil?.let { accueil ->
                Text(
                    text = stringResource(R.string.drift_home, accueil.nom),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onReprendre, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.drift_action))
            }
        }
    }
}

/** Les trois premiers noms, puis « … » : la liste entière est dans la confirmation. */
private fun resume(noms: List<String>): String =
    noms.take(NOMS_MONTRES).joinToString(", ") + if (noms.size > NOMS_MONTRES) ", …" else ""

private const val NOMS_MONTRES = 3
