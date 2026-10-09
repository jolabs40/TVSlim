package net.jolabs40.tvslim.shell

/** Output of a binary command: stdout byte for byte, stderr kept apart. */
class BinaryOutput(
    /** `null` when the command did not finish (timeout or lost connection). */
    val code: Int?,
    val bytes: ByteArray,
    val errors: String = "",
    /** Technical reason for the interruption. */
    val reason: String = "",
)

/**
 * Runs a command whose output is not text, such as `screencap -p` (a PNG).
 *
 * Separate from [CommandExecutor], which returns a string: a PNG decoded as UTF-8 is corrupted.
 * Nothing is replayed after a disconnect; a missed capture is one click away.
 */
interface BinaryReader {
    suspend fun readBinary(command: String): BinaryOutput
}
