package net.jolabs40.tvslim.shell

/** Why a run-once command returned no exit code. */
enum class Interruption { TIMEOUT, CONNECTION }

/** Output of a hand-typed command, even if it was cut off. */
data class DirectResponse(
    /** `null` when the command did not finish (timeout or lost connection). */
    val code: Int?,
    val output: String,
    val interruption: Interruption? = null,
    /** Technical reason for the interruption. */
    val reason: String = "",
)

/**
 * Runs a command that TV Slim did not write.
 *
 * Separate from [CommandExecutor], which replays its commands after a disconnect because they are known
 * to be safe to repeat. Nothing says that of a hand-typed command.
 */
interface DirectExecutor {
    /** Runs [command] exactly once and returns whatever it printed before any interruption. */
    suspend fun executeOnce(command: String): DirectResponse
}
