package net.jolabs40.tvslim.command

import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.DirectExecutor
import net.jolabs40.tvslim.shell.Interruption

/** Why typed input is not sent. */
enum class CommandRejection { EMPTY, NOT_SHELL, TOO_LONG }

sealed interface CommandInput {
    data class Ready(val command: String) : CommandInput

    data class Rejected(val rejection: CommandRejection) : CommandInput
}

data class CommandExchange(
    val command: String,
    val code: Int?,
    val output: String,
    val interruption: Interruption? = null,
    val reason: String = "",
    /** Length of the output received, before truncation for display. */
    val receivedLength: Int = output.length,
) {
    val succeeded: Boolean get() = code == 0
    val truncated: Boolean get() = receivedLength > output.length
}

/**
 * Free-form `adb shell` command typed by hand.
 *
 * The only path in TV Slim that bypasses every safeguard, on purpose: a blocklist is easy to evade
 * (`cmd package uninstall`, `sh -c "..."`) and would only give false assurance. Instead the command is sent
 * once, never replayed after a disconnect, and logged to the journal with no undo command.
 */
class AdbConsole(
    private val executor: DirectExecutor,
    private val journal: () -> JournalRepository?,
) {

    suspend fun send(command: String): CommandExchange {
        val response = executor.executeOnce(command)
        val exchange = CommandExchange(
            command = command,
            code = response.code,
            output = response.output.take(MAX_OUTPUT),
            interruption = response.interruption,
            reason = response.reason,
            receivedLength = response.output.length,
        )
        journal()?.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.COMMAND,
                target = command.take(MAX_TARGET_LENGTH),
                label = "Commande libre",
                undoCommand = "",
                succeeded = exchange.succeeded,
                message = if (exchange.succeeded) "" else failureReason(exchange),
            ),
        )
        return exchange
    }

    private fun failureReason(exchange: CommandExchange): String = when (exchange.interruption) {
        Interruption.TIMEOUT -> "Coupée par le délai maximal."
        Interruption.CONNECTION -> exchange.reason.ifBlank { "Connexion perdue." }
        null -> exchange.output.trim().take(MESSAGE_MAX).ifBlank { "Code de retour ${exchange.code}." }
    }

    companion object {
        /** Past this, the command is cut off and the output so far is returned. */
        const val MAX_TIMEOUT_S = 30

        /** Output beyond this is truncated for display: `dumpsys` alone prints hundreds of KB. */
        const val MAX_OUTPUT = 200_000

        const val MAX_LENGTH = 4_000
        private const val MAX_TARGET_LENGTH = 300
        private const val MESSAGE_MAX = 300

        /** `adb`, its options (those taking a value first), then `shell`. */
        private val WRAPPER = Regex("""^adb(?:\s+-[stHPL]\s+\S+|\s+-\S+)*\s+shell(?:\s+|$)""")

        /**
         * Returns the command to send, or why nothing is sent. `adb shell pm list packages` pasted from a
         * tutorial loses its `adb [-s <device>] shell` prefix, and a pair of quotes around the whole command
         * that the computer's shell would have removed. Other `adb` commands (`install`, `push`, `reboot`...)
         * do not run in the TV's shell and are rejected.
         */
        fun read(input: String): CommandInput {
            val text = input.trim()
            if (text.length > MAX_LENGTH) return CommandInput.Rejected(CommandRejection.TOO_LONG)
            val command = if (text == "adb" || text.startsWith("adb ")) {
                val wrapper = WRAPPER.find(text) ?: return CommandInput.Rejected(CommandRejection.NOT_SHELL)
                withoutQuotes(text.substring(wrapper.range.last + 1).trim())
            } else {
                text
            }
            return if (command.isEmpty()) {
                CommandInput.Rejected(CommandRejection.EMPTY)
            } else {
                CommandInput.Ready(command)
            }
        }

        private fun withoutQuotes(command: String): String {
            if (command.length < 2) return command
            val quote = command.first().takeIf { it == '\'' || it == '"' } ?: return command
            val unquoted = command.substring(1, command.length - 1)
            return if (command.last() == quote && quote !in unquoted) unquoted.trim() else command
        }
    }
}
