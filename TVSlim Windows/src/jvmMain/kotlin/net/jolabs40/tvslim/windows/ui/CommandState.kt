package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.command.CommandExchange

/** State of the "ADB command" card. */
@Immutable
data class CommandState(
    val input: String = "",
    val inProgress: Boolean = false,
    /** Last command and its output, kept on screen until the next one. */
    val last: CommandExchange? = null,
    /** Sent commands, most recent first, without duplicates. */
    val history: List<String> = emptyList(),
    /** Index in [history] recalled with Up; -1 while typing. */
    val recall: Int = -1,
)

data class CommandActions(
    val onInput: (String) -> Unit,
    val onSend: () -> Unit,
    val onRecall: (older: Boolean) -> Unit,
)

/**
 * Shell-style history: Up goes to older commands, Down to newer ones and clears the field past the most
 * recent. Down does nothing to the current input if nothing was recalled.
 */
fun CommandState.withRecall(older: Boolean): CommandState {
    if (history.isEmpty() || (!older && recall < 0)) return this
    val index = if (older) minOf(recall + 1, history.lastIndex) else recall - 1
    return if (index < 0) copy(input = "", recall = -1) else copy(input = history[index], recall = index)
}

/** Puts a sent command at the top of the history. */
fun CommandState.withSentCommand(command: String): CommandState = copy(
    history = (listOf(command) + history.filterNot { it == command }).take(MAX_HISTORY),
    recall = -1,
)

private const val MAX_HISTORY = 20
