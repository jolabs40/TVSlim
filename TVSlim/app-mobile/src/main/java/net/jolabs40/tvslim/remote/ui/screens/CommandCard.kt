package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.command.CommandExchange
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.CommandActions
import net.jolabs40.tvslim.remote.ui.CommandState
import net.jolabs40.tvslim.shell.Interruption

/**
 * A shell command typed by hand, for whatever the other cards do not cover.
 *
 * It bypasses every safeguard, and the card says so first. No autocorrect or capitalization; the output stays
 * selectable until the next command.
 */
@Composable
fun CommandCard(state: CommandState, actions: CommandActions) {
    // Red border: the only way around the safeguards must not look like the other cards.
    Card(modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    text = stringResource(R.string.command_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.command_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            OutlinedTextField(
                value = state.input,
                onValueChange = actions.onInput,
                label = { Text(stringResource(R.string.command_label)) },
                placeholder = { Text(stringResource(R.string.command_placeholder), fontFamily = FontFamily.Monospace) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { actions.onSend() }),
                trailingIcon = if (state.history.isEmpty()) null else {
                    { CommandHistory(state.history, actions.onInput) }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = actions.onSend, enabled = !state.inProgress && state.input.isNotBlank()) {
                    Text(stringResource(R.string.command_send))
                }
                if (state.inProgress) CircularProgressIndicator()
            }
            if (state.inProgress) {
                Text(
                    text = stringResource(R.string.command_running, AdbConsole.MAX_TIMEOUT_S),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.last?.let { CommandOutput(it) }
        }
    }
}

@Composable
private fun CommandHistory(history: List<String>, onChoose: (String) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { opened = true }) {
            Icon(Icons.Filled.History, contentDescription = stringResource(R.string.command_history))
        }
        DropdownMenu(expanded = opened, onDismissRequest = { opened = false }) {
            history.forEach { previous ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = previous,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        opened = false
                        onChoose(previous)
                    },
                )
            }
        }
    }
}

/** The command, how it ended, and its output (partial if it was cut off). */
@Composable
private fun CommandOutput(exchange: CommandExchange) {
    val (status, color) = when {
        exchange.interruption == Interruption.TIMEOUT ->
            stringResource(R.string.command_cut_delay, AdbConsole.MAX_TIMEOUT_S) to MaterialTheme.colorScheme.error

        exchange.interruption == Interruption.CONNECTION ->
            stringResource(R.string.command_cut_connection) to MaterialTheme.colorScheme.error

        exchange.succeeded -> stringResource(R.string.command_exit, 0) to MaterialTheme.colorScheme.primary
        else -> stringResource(R.string.command_exit, exchange.code ?: -1) to MaterialTheme.colorScheme.error
    }
    val scrollState = remember(exchange) { ScrollState(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "$ ${exchange.command}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = status, style = MaterialTheme.typography.labelMedium, color = color)
            if (exchange.interruption == Interruption.CONNECTION && exchange.reason.isNotBlank()) {
                Text(
                    text = exchange.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(
                    text = exchange.output.ifEmpty { stringResource(R.string.command_no_output) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scrollState),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (exchange.truncated) {
                Text(
                    text = stringResource(R.string.command_truncated, exchange.output.length, exchange.receivedLength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
