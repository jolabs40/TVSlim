package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.command.CommandExchange

/** State of the "ADB command" card. */
@Immutable
data class CommandState(
    val input: String = "",
    val inProgress: Boolean = false,
    /** Last command and its output, shown until the next one. */
    val last: CommandExchange? = null,
    /** Sent commands, most recent first, without duplicates; offered in the field's menu. */
    val history: List<String> = emptyList(),
)

data class CommandActions(
    val onInput: (String) -> Unit,
    val onSend: () -> Unit,
)

/** Moves a sent command to the top of the history. */
fun CommandState.withSentCommand(command: String): CommandState = copy(
    history = (listOf(command) + history.filterNot { it == command }).take(MAX_HISTORY),
)

private const val MAX_HISTORY = 20
