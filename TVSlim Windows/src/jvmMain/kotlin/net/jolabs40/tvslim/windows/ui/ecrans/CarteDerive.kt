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
 * Le téléviseur a défait seul une partie de ce que TV Slim avait réglé — cf. `planDeDerive`.
 *
 * Elle ne dit que l'essentiel, et un seul bouton : le détail paquet par paquet, effets de bord compris,
 * vient dans la confirmation, la même que pour une configuration réinjectée.
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

/** Les trois premiers noms, puis « … » : la liste entière est dans la confirmation. */
private fun resume(noms: List<String>): String =
    noms.take(NOMS_MONTRES).joinToString(", ") + if (noms.size > NOMS_MONTRES) ", …" else ""

private const val NOMS_MONTRES = 3
