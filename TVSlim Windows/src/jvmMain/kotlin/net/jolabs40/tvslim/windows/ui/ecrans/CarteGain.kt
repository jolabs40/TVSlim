package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.gain_before
import net.jolabs40.tvslim.windows.ressources.gain_delta_minus
import net.jolabs40.tvslim.windows.ressources.gain_delta_plus
import net.jolabs40.tvslim.windows.ressources.gain_memory
import net.jolabs40.tvslim.windows.ressources.gain_memory_caveat
import net.jolabs40.tvslim.windows.ressources.gain_now
import net.jolabs40.tvslim.windows.ressources.gain_packages
import net.jolabs40.tvslim.windows.ressources.gain_reset
import net.jolabs40.tvslim.windows.ressources.gain_since
import net.jolabs40.tvslim.windows.ressources.gain_title
import net.jolabs40.tvslim.windows.ressources.gain_waiting
import net.jolabs40.tvslim.windows.ressources.memory_mb
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import org.jetbrains.compose.resources.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the debloat changed, measured rather than promised.
 *
 * The first reading on a TV becomes the baseline and every later one, across sessions, is compared to it:
 * a single free-memory figure means nothing on its own.
 */
@Composable
fun CarteGain(
    mesures: HistoriqueMesures,
    onRedefinirReference: () -> Unit,
) {
    val reference = mesures.reference ?: return

    CarteSection(
        titre = stringResource(Res.string.gain_title),
        couleurs = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = stringResource(Res.string.gain_since, dateCourte(reference.horodatage)),
            style = MaterialTheme.typography.bodySmall,
        )

        val derniere = mesures.derniere
        if (!mesures.comparable || derniere == null) {
            Text(text = stringResource(Res.string.gain_waiting), style = MaterialTheme.typography.bodyMedium)
            return@CarteSection
        }

        Comparaison(
            libelle = stringResource(Res.string.gain_packages),
            avant = reference.paquetsDesactives.toString(),
            maintenant = derniere.paquetsDesactives.toString(),
            delta = mesures.paquetsDesactivesEnPlus.toLong(),
        )
        Comparaison(
            libelle = stringResource(Res.string.gain_memory),
            avant = stringResource(Res.string.memory_mb, reference.memoireLibreMo),
            maintenant = stringResource(Res.string.memory_mb, derniere.memoireLibreMo),
            delta = mesures.memoireLibreEnPlusMo,
        )
        Text(text = stringResource(Res.string.gain_memory_caveat), style = MaterialTheme.typography.bodySmall)

        TextButton(onClick = onRedefinirReference) {
            Text(stringResource(Res.string.gain_reset))
        }
    }
}

@Composable
private fun Comparaison(libelle: String, avant: String, maintenant: String, delta: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = libelle, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${stringResource(Res.string.gain_before)} $avant · ${stringResource(Res.string.gain_now)} $maintenant",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Losses are shown as plainly as gains.
        Text(
            text = if (delta >= 0) {
                stringResource(Res.string.gain_delta_plus, delta)
            } else {
                stringResource(Res.string.gain_delta_minus, delta)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

private fun dateCourte(horodatage: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(horodatage))
