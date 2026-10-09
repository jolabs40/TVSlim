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
 * Shown when the TV has undone part of TV Slim's changes on its own (see `planDeDerive`).
 *
 * The per-package detail, side effects included, is left to the confirmation shared with configuration restore.
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

private fun resume(noms: List<String>): String =
    noms.take(NOMS_MONTRES).joinToString(", ") + if (noms.size > NOMS_MONTRES) ", …" else ""

private const val NOMS_MONTRES = 3
