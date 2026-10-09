package net.jolabs40.tvslim.shell

import java.io.OutputStream

/**
 * Reads a file from the TV like `adb pull`, over the ADB sync protocol ([FileUploader] in reverse).
 *
 * Separate from [CommandExecutor] for the same reason: a multi-gigabyte copy must never be replayed behind the
 * user's back. Only the Windows connection implements it.
 */
interface FileReceiver {
    /**
     * Writes the contents of [path] to [destination], which the caller closes. [size] is only for progress
     * (0 when unknown). [cancelled] is checked on every block.
     *
     * Returns code 0 when everything arrived, code 1 with the TV's own message when it refused
     * (`open failed: Permission denied`), or [ShellResult.unavailable] when the connection dropped or the
     * copy was cancelled, in which case [destination] holds only part of the file.
     */
    suspend fun receive(
        path: String,
        destination: OutputStream,
        size: Long,
        cancelled: () -> Boolean,
        onReceived: (received: Long) -> Unit,
    ): ShellResult
}
