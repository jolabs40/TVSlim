package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.command.CommandExchange
import net.jolabs40.tvslim.command.ShizukuRelaunch
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ShizukuActions
import net.jolabs40.tvslim.remote.ui.ShizukuState

/**
 * Restarts the TV's Shizuku service, which dies at every power-off.
 *
 * TV Slim itself does not use Shizuku: its ADB authorization survives reboots. The card is for other TV apps that
 * depend on it, since the service runs with shell privileges and nothing on the TV can restart it.
 *
 * One button, no input: the command is a core constant, so unlike the ADB command card there is nothing to check.
 * The raw output stays on screen, as it carries the pid or the refusal.
 */
@Composable
fun ShizukuCard(state: ShizukuState, actions: ShizukuActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.shizuku_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.shizuku_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = actions.onRelaunch, enabled = !state.inProgress) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.shizuku_restart))
                }
                if (state.inProgress) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
            state.last?.let { Summary(it) }
        }
    }
}

/**
 * Exit code 0 does not mean it started: the starter returns 0 even when it gives up. Only its output announces the
 * pid ([ShizukuRelaunch.started]), so a silent `0` counts as a failure.
 */
@Composable
private fun Summary(exchange: CommandExchange) {
    val succeeded = exchange.succeeded && ShizukuRelaunch.started(exchange.output)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (succeeded) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(if (succeeded) R.string.shizuku_started else R.string.shizuku_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    if (exchange.output.isNotBlank()) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            SelectionContainer {
                Text(
                    text = exchange.output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(12.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
