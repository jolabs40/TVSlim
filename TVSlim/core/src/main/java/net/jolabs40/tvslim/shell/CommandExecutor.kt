package net.jolabs40.tvslim.shell

/** Raw result of a command run on the TV. */
data class ShellResult(
    val code: Int,
    val output: String,
) {
    val succeeded: Boolean get() = code == 0

    companion object {
        fun unavailable(reason: String) = ShellResult(code = -1, output = reason)
    }
}

/**
 * Privileged execution channel to a TV.
 *
 * The debloat engine only depends on this interface, so the transport (a local Shizuku service or an ADB
 * connection from a phone) can change without touching the catalogue, the safeguards or the journal.
 */
interface CommandExecutor {
    /** Runs a shell command and returns its exit code with stdout and stderr merged. */
    suspend fun execute(command: String): ShellResult
}
