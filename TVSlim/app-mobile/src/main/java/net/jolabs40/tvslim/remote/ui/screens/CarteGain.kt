package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.mesure.HistoriqueMesures
import net.jolabs40.tvslim.remote.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The measured effect of the debloat. The first reading on a TV is the baseline, and later readings are compared to
 * it across sessions: a single free-memory figure says nothing.
 */
@Composable
fun CarteGain(
    mesures: HistoriqueMesures,
    onRedefinirReference: () -> Unit,
) {
    val reference = mesures.reference ?: return

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.gain_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.gain_since, dateCourte(reference.horodatage)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            if (!mesures.comparable) {
                Text(
                    text = stringResource(R.string.gain_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val derniere = mesures.derniere!!
            Comparaison(
                libelle = stringResource(R.string.gain_packages),
                avant = reference.paquetsDesactives.toString(),
                maintenant = derniere.paquetsDesactives.toString(),
                delta = mesures.paquetsDesactivesEnPlus.toLong(),
            )
            Comparaison(
                libelle = stringResource(R.string.gain_memory),
                avant = "${reference.memoireLibreMo} Mo",
                maintenant = "${derniere.memoireLibreMo} Mo",
                delta = mesures.memoireLibreEnPlusMo,
            )
            Text(
                text = stringResource(R.string.gain_memory_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            TextButton(onClick = onRedefinirReference) {
                Text(stringResource(R.string.gain_reset))
            }
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
                text = "${stringResource(R.string.gain_before)} $avant · " +
                    "${stringResource(R.string.gain_now)} $maintenant",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        // A loss is shown as plainly as a gain.
        Text(
            text = if (delta >= 0) {
                stringResource(R.string.gain_delta_plus, delta)
            } else {
                stringResource(R.string.gain_delta_minus, delta)
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (delta >= 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun dateCourte(horodatage: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(horodatage))
