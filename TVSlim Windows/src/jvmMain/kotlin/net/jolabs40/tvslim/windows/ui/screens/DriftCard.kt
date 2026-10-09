package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.drift_action
import net.jolabs40.tvslim.windows.resources.drift_body
import net.jolabs40.tvslim.windows.resources.drift_home
import net.jolabs40.tvslim.windows.resources.drift_packages
import net.jolabs40.tvslim.windows.resources.drift_title
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when the TV undid part of what TV Slim set (see `driftPlan`).
 *
 * Only a summary and one button: the per-package detail, side effects included, is in the confirmation
 * dialog, the same one used to reapply a configuration.
 */
@Composable
fun DriftCard(plan: ReinjectionPlan, onRevert: () -> Unit) {
    SectionCard(
        title = stringResource(Res.string.drift_title),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(text = stringResource(Res.string.drift_body), style = MaterialTheme.typography.bodyMedium)
        if (plan.toDisable.isNotEmpty()) {
            Text(
                text = stringResource(
                    Res.string.drift_packages,
                    plan.toDisable.size,
                    summary(plan.toDisable.map { it.name }),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        plan.home?.let { home ->
            Text(
                text = stringResource(Res.string.drift_home, home.name),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onRevert, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(Res.string.drift_action))
        }
    }
}

/** First three names, then "…"; the full list is in the confirmation. */
private fun summary(names: List<String>): String =
    names.take(MAX_SHOWN_NAMES).joinToString(", ") + if (names.size > MAX_SHOWN_NAMES) ", …" else ""

private const val MAX_SHOWN_NAMES = 3
