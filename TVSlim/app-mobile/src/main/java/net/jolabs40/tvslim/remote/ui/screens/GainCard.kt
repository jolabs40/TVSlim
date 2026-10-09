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
import net.jolabs40.tvslim.measurement.MeasurementHistory
import net.jolabs40.tvslim.remote.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The measured effect of the debloat. The first reading on a TV is the baseline, and later readings are compared to
 * it across sessions: a single free-memory figure says nothing.
 */
@Composable
fun GainCard(
    measurements: MeasurementHistory,
    onResetReference: () -> Unit,
) {
    val reference = measurements.reference ?: return

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
                text = stringResource(R.string.gain_since, shortDate(reference.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            if (!measurements.comparable) {
                Text(
                    text = stringResource(R.string.gain_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }

            val last = measurements.last!!
            Comparison(
                label = stringResource(R.string.gain_packages),
                before = reference.disabledPackages.toString(),
                now = last.disabledPackages.toString(),
                delta = measurements.extraDisabledPackages.toLong(),
            )
            Comparison(
                label = stringResource(R.string.gain_memory),
                before = "${reference.freeMemoryMb} Mo",
                now = "${last.freeMemoryMb} Mo",
                delta = measurements.extraFreeMemoryMb,
            )
            Text(
                text = stringResource(R.string.gain_memory_caveat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            TextButton(onClick = onResetReference) {
                Text(stringResource(R.string.gain_reset))
            }
        }
    }
}

@Composable
private fun Comparison(label: String, before: String, now: String, delta: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${stringResource(R.string.gain_before)} $before · " +
                    "${stringResource(R.string.gain_now)} $now",
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

private fun shortDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
