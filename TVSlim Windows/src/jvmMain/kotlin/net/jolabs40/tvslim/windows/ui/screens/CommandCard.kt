package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.command.CommandExchange
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.command_cut_connection
import net.jolabs40.tvslim.windows.resources.command_cut_delay
import net.jolabs40.tvslim.windows.resources.command_exit
import net.jolabs40.tvslim.windows.resources.command_keys
import net.jolabs40.tvslim.windows.resources.command_label
import net.jolabs40.tvslim.windows.resources.command_no_output
import net.jolabs40.tvslim.windows.resources.command_placeholder
import net.jolabs40.tvslim.windows.resources.command_running
import net.jolabs40.tvslim.windows.resources.command_send
import net.jolabs40.tvslim.windows.resources.command_title
import net.jolabs40.tvslim.windows.resources.command_truncated
import net.jolabs40.tvslim.windows.resources.command_warning
import net.jolabs40.tvslim.windows.ui.CommandActions
import net.jolabs40.tvslim.windows.ui.CommandState
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.stringResource

/**
 * A shell command typed by hand, for what the other cards do not cover.
 *
 * It bypasses the safeguards, and the card says so first. Enter sends, Up and Down recall earlier commands;
 * the output stays on screen, selectable, until the next one.
 */
@Composable
fun CommandCard(state: CommandState, actions: CommandActions) {
    SectionCard(title = stringResource(Res.string.command_title), spacing = 12.dp) {
        Text(
            text = stringResource(Res.string.command_warning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        OutlinedTextField(
            value = state.input,
            onValueChange = actions.onInput,
            label = { Text(stringResource(Res.string.command_label)) },
            placeholder = { Text(stringResource(Res.string.command_placeholder), fontFamily = FontFamily.Monospace) },
            supportingText = { Text(stringResource(Res.string.command_keys)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        actions.onSend()
                        true
                    }

                    Key.DirectionUp -> {
                        actions.onRecall(true)
                        true
                    }

                    Key.DirectionDown -> {
                        actions.onRecall(false)
                        true
                    }

                    else -> false
                }
            },
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = actions.onSend, enabled = !state.inProgress && state.input.isNotBlank()) {
                Text(stringResource(Res.string.command_send))
            }
            if (state.inProgress) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                SecondaryText(stringResource(Res.string.command_running, AdbConsole.MAX_TIMEOUT_S), small = true)
            }
        }

        state.last?.let { CommandOutput(it) }
    }
}

/** The command, how it ended, and its output, even when cut off. */
@Composable
private fun CommandOutput(exchange: CommandExchange) {
    val (status, color) = when {
        exchange.interruption == Interruption.TIMEOUT ->
            stringResource(Res.string.command_cut_delay, AdbConsole.MAX_TIMEOUT_S) to MaterialTheme.colorScheme.error

        exchange.interruption == Interruption.CONNECTION ->
            stringResource(Res.string.command_cut_connection) to MaterialTheme.colorScheme.error

        exchange.succeeded -> stringResource(Res.string.command_exit, 0) to MaterialTheme.colorScheme.primary
        else -> stringResource(Res.string.command_exit, exchange.code ?: -1) to MaterialTheme.colorScheme.error
    }
    val scrollState = remember(exchange) { ScrollState(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "$ ${exchange.command}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = status, style = MaterialTheme.typography.labelMedium, color = color)
            }
            if (exchange.interruption == Interruption.CONNECTION && exchange.reason.isNotBlank()) {
                SecondaryText(exchange.reason, small = true)
            }
            SelectionContainer {
                Text(
                    text = exchange.output.ifEmpty { stringResource(Res.string.command_no_output) },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(scrollState),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (exchange.truncated) {
                SecondaryText(
                    stringResource(Res.string.command_truncated, exchange.output.length, exchange.receivedLength),
                    small = true,
                )
            }
        }
    }
}
