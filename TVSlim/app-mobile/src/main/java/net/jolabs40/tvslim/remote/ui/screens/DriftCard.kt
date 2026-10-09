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
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.remote.R

/**
 * Shown when the TV has undone part of TV Slim's changes on its own (see `driftPlan`).
 *
 * The per-package detail, side effects included, is left to the confirmation shared with configuration restore.
 */
@Composable
fun DriftCard(plan: ReinjectionPlan, onRevert: () -> Unit) {
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
            if (plan.toDisable.isNotEmpty()) {
                Text(
                    text = pluralStringResource(
                        R.plurals.drift_packages,
                        plan.toDisable.size,
                        plan.toDisable.size,
                        summary(plan.toDisable.map { it.name }),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            plan.home?.let { home ->
                Text(
                    text = stringResource(R.string.drift_home, home.name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onRevert, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.drift_action))
            }
        }
    }
}

private fun summary(names: List<String>): String =
    names.take(MAX_SHOWN_NAMES).joinToString(", ") + if (names.size > MAX_SHOWN_NAMES) ", …" else ""

private const val MAX_SHOWN_NAMES = 3
