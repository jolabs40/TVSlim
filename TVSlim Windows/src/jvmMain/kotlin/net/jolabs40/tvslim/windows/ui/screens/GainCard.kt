package net.jolabs40.tvslim.windows.ui.screens

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
import net.jolabs40.tvslim.measurement.MeasurementHistory
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.gain_before
import net.jolabs40.tvslim.windows.resources.gain_delta_minus
import net.jolabs40.tvslim.windows.resources.gain_delta_plus
import net.jolabs40.tvslim.windows.resources.gain_memory
import net.jolabs40.tvslim.windows.resources.gain_memory_caveat
import net.jolabs40.tvslim.windows.resources.gain_now
import net.jolabs40.tvslim.windows.resources.gain_packages
import net.jolabs40.tvslim.windows.resources.gain_reset
import net.jolabs40.tvslim.windows.resources.gain_since
import net.jolabs40.tvslim.windows.resources.gain_title
import net.jolabs40.tvslim.windows.resources.gain_waiting
import net.jolabs40.tvslim.windows.resources.memory_mb
import net.jolabs40.tvslim.windows.ui.components.SectionCard
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
fun GainCard(
    measurements: MeasurementHistory,
    onResetReference: () -> Unit,
) {
    val reference = measurements.reference ?: return

    SectionCard(
        title = stringResource(Res.string.gain_title),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = stringResource(Res.string.gain_since, shortDate(reference.timestamp)),
            style = MaterialTheme.typography.bodySmall,
        )

        val last = measurements.last
        if (!measurements.comparable || last == null) {
            Text(text = stringResource(Res.string.gain_waiting), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        Comparison(
            label = stringResource(Res.string.gain_packages),
            before = reference.disabledPackages.toString(),
            now = last.disabledPackages.toString(),
            delta = measurements.extraDisabledPackages.toLong(),
        )
        Comparison(
            label = stringResource(Res.string.gain_memory),
            before = stringResource(Res.string.memory_mb, reference.freeMemoryMb),
            now = stringResource(Res.string.memory_mb, last.freeMemoryMb),
            delta = measurements.extraFreeMemoryMb,
        )
        Text(text = stringResource(Res.string.gain_memory_caveat), style = MaterialTheme.typography.bodySmall)

        TextButton(onClick = onResetReference) {
            Text(stringResource(Res.string.gain_reset))
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
                text = "${stringResource(Res.string.gain_before)} $before · ${stringResource(Res.string.gain_now)} $now",
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

private fun shortDate(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
