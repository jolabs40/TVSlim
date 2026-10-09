package net.jolabs40.tvslim.shell

import java.io.InputStream

/**
 * Writes a file to the TV like `adb push`, over the ADB sync protocol rather than the shell: a multi-gigabyte
 * video does not fit in a command line.
 *
 * Separate from [CommandExecutor] for the same reason as [ApkInstaller]: a transfer must never be replayed
 * behind the user's back. Each app's ADB connection implements all three.
 */
interface FileUploader {
    /**
     * Writes [source] to [path], replacing any existing file, and always closes [source]. [size] is only
     * for progress (0 when unknown); [date] is in milliseconds. [cancelled] is checked on every block.
     *
     * Returns code 0 when the TV received everything, code 1 with the TV's own message when it refused
     * (`couldn't create file: Permission denied`), or [ShellResult.unavailable] when the connection dropped
     * or the transfer was cancelled.
     */
    suspend fun send(
        source: InputStream,
        size: Long,
        path: String,
        date: Long,
        cancelled: () -> Boolean,
        onSent: (sent: Long) -> Unit,
    ): ShellResult
}
