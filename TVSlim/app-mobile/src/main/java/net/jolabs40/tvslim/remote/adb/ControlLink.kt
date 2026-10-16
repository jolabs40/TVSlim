package net.jolabs40.tvslim.remote.adb

import android.util.Log
import dadb.AdbShellPacket
import dadb.AdbShellStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.control.ControlChannel
import net.jolabs40.tvslim.control.ControlProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The remote control's link to its helper: a dedicated ADB session, the helper pushed and started on it, and the
 * helper's standard input and output as lines. Standard input belongs to this stream alone, so nothing else on the
 * TV can send keys through the helper, and nothing new listens on the network.
 */
class ControlLink private constructor(
    private val session: AdbClient,
    private val stream: AdbShellStream,
) : ControlChannel {

    private val writing = Mutex()
    private val partial = ByteArrayOutputStream()
    private val lines = ArrayDeque<String>()

    override suspend fun write(line: String) = withContext(Dispatchers.IO) {
        writing.withLock { stream.write(STDIN, (line + "\n").toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        while (lines.isEmpty()) {
            when (val packet = stream.read()) {
                is AdbShellPacket.StdOut -> split(packet.payload)
                is AdbShellPacket.StdError -> Log.d(TAG, "helper: " + String(packet.payload, Charsets.UTF_8).trim())
                is AdbShellPacket.Exit -> return@withContext null
                else -> Unit
            }
        }
        lines.removeFirst()
    }

    private fun split(bytes: ByteArray) {
        for (byte in bytes) {
            if (byte == NEWLINE) {
                lines.addLast(String(partial.toByteArray(), Charsets.UTF_8))
                partial.reset()
            } else {
                partial.write(byte.toInt())
            }
        }
    }

    /** Closing the stream closes the helper's input: it releases what is held and stops. */
    override fun close() {
        runCatching { stream.close() }
    }

    /**
     * Deletes the helper if it never got to delete itself, then closes the session. The deletion gets a short wait:
     * on a TV that has just gone away it would block, and closing the session is what unblocks it.
     */
    suspend fun finish(scope: CoroutineScope, clean: Boolean) {
        close()
        if (clean) {
            val cleanup = scope.launch(Dispatchers.IO) { session.execute("rm -f ${ControlProtocol.HELPER_PATH}") }
            withTimeoutOrNull(CLEANUP_WAIT_MS) { cleanup.join() }
        }
        session.disconnect()
    }

    sealed interface Opening {
        data class Opened(val link: ControlLink) : Opening

        data class Failed(val problem: ConnectionProblem?, val detail: String) : Opening
    }

    companion object {
        private const val TAG = "TVSlim/Remote"

        /** Shell protocol v2 stream id of standard input. */
        private const val STDIN = 0
        private const val NEWLINE = '\n'.code.toByte()
        private const val CLEANUP_WAIT_MS = 2_000L

        /** Opens a second session to the connected TV, pushes the helper there and starts it. */
        suspend fun open(main: AdbClient, helper: () -> InputStream?): Opening {
            val bytes = runCatching { helper()?.use { it.readBytes() } }.getOrNull()
                ?: return Opening.Failed(null, "helper missing")
            val session = main.openSecond() ?: return Opening.Failed(ConnectionProblem.UNREACHABLE, "")
            var opened = false
            try {
                val upload = session.send(
                    ByteArrayInputStream(bytes),
                    bytes.size.toLong(),
                    ControlProtocol.HELPER_PATH,
                    System.currentTimeMillis(),
                    { false },
                    {},
                )
                if (upload.code != 0) return Opening.Failed(ConnectionProblem.OTHER, upload.output)
                val stream = session.openStream(ControlProtocol.COMMAND)
                    ?: return Opening.Failed(ConnectionProblem.UNREACHABLE, "")
                opened = true
                return Opening.Opened(ControlLink(session, stream))
            } finally {
                // Also when cancelled midway: a session left open would keep a helper running.
                if (!opened) session.disconnect()
            }
        }
    }
}
