package net.jolabs40.tvslim.screen

import kotlinx.coroutines.delay
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.FileReceiver
import net.jolabs40.tvslim.shell.ShellResult
import java.io.OutputStream

/** Why a recording did not start or produced nothing; each app localizes the message. */
enum class RecordingCause {
    /** No session, or the connection dropped. */
    CONNECTION,

    /** The TV has no `screenrecord`. */
    UNAVAILABLE,

    /** `screenrecord` exited right after starting (encoder refused, protected screen...). */
    FAILED,

    /** Stopped, but no file or an empty one. */
    EMPTY,
}

sealed interface RecordingStart {
    /** [limitSeconds]: maximum duration enforced before Android 14, or `null` when there is none. */
    data class Started(val limitSeconds: Int?) : RecordingStart

    data class Rejected(val cause: RecordingCause, val detail: String) : RecordingStart
}

sealed interface RecordingStop {
    data class Done(val size: Long) : RecordingStop

    data class Rejected(val cause: RecordingCause, val detail: String) : RecordingStop
}

/**
 * Records the screen on the TV itself with its own `screenrecord`, then copies the video.
 *
 * Chosen over scrcpy: no third-party software, no second ADB authorization, nothing crosses the network while
 * recording. The cost: no audio (`screenrecord` never captures it), a copy to wait for at the end, and a
 * 3-minute limit before Android 14.
 *
 * The recorder runs detached (`setsid`), so it outlives the command that started it and even a lost ADB
 * session. It is stopped with `SIGINT`, which makes it write the MP4 index; `SIGKILL` would leave an
 * unreadable file.
 */
class TvRecording(
    private val executor: CommandExecutor,
    private val fileReceiver: FileReceiver,
) {

    suspend fun start(): RecordingStart {
        val helpOutput = executor.execute("screenrecord --help 2>&1")
        if (helpOutput.code < 0) return RecordingStart.Rejected(RecordingCause.CONNECTION, helpOutput.output)
        if (!helpOutput.output.contains("screenrecord", ignoreCase = true) || helpOutput.output.contains("not found")) {
            return RecordingStart.Rejected(RecordingCause.UNAVAILABLE, helpOutput.output.trim())
        }
        // "Set to 0 to remove the time limit" appears from Android 14; before that, 180 s at most.
        val limitSeconds = if (helpOutput.output.contains("Set to 0")) null else LEGACY_LIMIT_S

        // Stops and deletes a recording left by a previous session (TV Slim force-closed).
        val cleanup = executor.execute("$STOP_PREVIOUS; rm -f $VIDEO $PID $JOURNAL")
        if (cleanup.code < 0) return RecordingStart.Rejected(RecordingCause.CONNECTION, cleanup.output)

        // No-op if the recorder is already running, so a command replayed after a disconnect does not start a
        // second one on the same file.
        val launch = executor.execute(
            "p=\$(cat $PID 2>/dev/null); if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; then echo deja; else " +
                "setsid sh -c 'echo \$\$ > $PID; exec screenrecord --time-limit ${limitSeconds ?: 0} --bit-rate $BITRATE $VIDEO' " +
                "> $JOURNAL 2>&1 < /dev/null & fi",
        )
        if (launch.code < 0) return RecordingStart.Rejected(RecordingCause.CONNECTION, launch.output)

        // An encoder that rejects the resolution, or a protected screen, stops it within a second.
        delay(START_WAIT_MS)
        return when (isAlive()) {
            true -> RecordingStart.Started(limitSeconds)
            null -> RecordingStart.Rejected(RecordingCause.CONNECTION, "")
            false -> RecordingStart.Rejected(RecordingCause.FAILED, journal())
        }
    }

    /** Whether the recorder is still running; `null` when the TV does not answer. */
    suspend fun isAlive(): Boolean? {
        val response = executor.execute("p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null")
        return when {
            response.code < 0 -> null
            else -> response.code == 0
        }
    }

    /**
     * Stops the recorder with `SIGINT`, waits for it to finish writing, and returns the video size. If it
     * already stopped (time limit reached), only the size is read.
     */
    suspend fun stop(): RecordingStop {
        val response = executor.execute(
            "p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -INT \"\$p\" 2>/dev/null; " +
                "i=0; while [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null && [ \$i -lt 50 ]; do sleep 0.2; i=\$((i+1)); done; " +
                "stat -c %s $VIDEO 2>/dev/null || echo 0",
        )
        if (response.code < 0) return RecordingStop.Rejected(RecordingCause.CONNECTION, response.output)
        val size = response.output.lines().lastOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: 0L
        return if (size > 0) RecordingStop.Done(size) else RecordingStop.Rejected(RecordingCause.EMPTY, journal())
    }

    /** Copies the video from the TV into [destination]; the caller closes it. */
    suspend fun download(
        destination: OutputStream,
        size: Long,
        cancelled: () -> Boolean = { false },
        onReceived: (Long) -> Unit = {},
    ): ShellResult = fileReceiver.receive(VIDEO, destination, size, cancelled, onReceived)

    /** Deletes the video and its tracking files from the TV once the copy is done. */
    suspend fun clean(): ShellResult = executor.execute("rm -f $VIDEO $PID $JOURNAL")

    private suspend fun journal(): String =
        executor.execute("cat $JOURNAL 2>/dev/null").output.trim().lines().takeLast(3).joinToString(" ")

    companion object {
        /** In the ADB shell's folder: invisible to TV apps and never media-scanned. */
        const val VIDEO = "/data/local/tmp/tvslim-enregistrement.mp4"
        const val PID = "/data/local/tmp/tvslim-enregistrement.pid"
        const val JOURNAL = "/data/local/tmp/tvslim-enregistrement.log"

        /** 8 Mbit/s, same as scrcpy: at most 60 MB per minute, much less on a static screen. */
        const val BITRATE = "8M"
        const val LEGACY_LIMIT_S = 180
        const val START_WAIT_MS = 1_000L

        private const val STOP_PREVIOUS =
            "p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -INT \"\$p\" 2>/dev/null && sleep 1"
    }
}
