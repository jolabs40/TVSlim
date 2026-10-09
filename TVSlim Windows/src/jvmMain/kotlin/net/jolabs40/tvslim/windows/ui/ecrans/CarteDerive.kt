package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.drift_action
import net.jolabs40.tvslim.windows.ressources.drift_body
import net.jolabs40.tvslim.windows.ressources.drift_home
import net.jolabs40.tvslim.windows.ressources.drift_packages
import net.jolabs40.tvslim.windows.ressources.drift_title
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import org.jetbrains.compose.resources.stringResource

/**
 * Shown when the TV undid part of what TV Slim set (see `planDeDerive`).
 *
 * Only a summary and one button: the per-package detail, side effects included, is in the confirmation
 * dialog, the same one used to reapply a configuration.
 */
@Composable
fun CarteDerive(plan: PlanReinjection, onReprendre: () -> Unit) {
    CarteSection(
        titre = stringResource(Res.string.drift_title),
        couleurs = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(text = stringResource(Res.string.drift_body), style = MaterialTheme.typography.bodyMedium)
        if (plan.aDesactiver.isNotEmpty()) {
            Text(
                text = stringResource(
                    Res.string.drift_packages,
                    plan.aDesactiver.size,
                    resume(plan.aDesactiver.map { it.nom }),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        plan.accueil?.let { accueil ->
            Text(
                text = stringResource(Res.string.drift_home, accueil.nom),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onReprendre, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(Res.string.drift_action))
        }
    }
}

/** First three names, then "…"; the full list is in the confirmation. */
private fun resume(noms: List<String>): String =
    noms.take(NOMS_MONTRES).joinToString(", ") + if (noms.size > NOMS_MONTRES) ", …" else ""

private const val NOMS_MONTRES = 3
