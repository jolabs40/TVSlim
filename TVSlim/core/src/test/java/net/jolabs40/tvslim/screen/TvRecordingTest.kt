package net.jolabs40.tvslim.screen

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.FileReceiver
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * On-TV recording against a fake TV: commands sent, their order, and the outcome. The real `screenrecord` is
 * covered on the TCL by `ScreenHardwareTest` (Windows).
 */
class TvRecordingTest {

    private val help14 = """
        Usage: screenrecord [options] <filename>
        --time-limit TIME
            Set the maximum recording time, in seconds.  Default is 180. Set to 0
            to remove the time limit.
    """.trimIndent()

    private val help11 = """
        Usage: screenrecord [options] <filename>
        --time-limit TIME
            Set the maximum recording time, in seconds.  Default / maximum is 180.
    """.trimIndent()

    private class FakeTv(
        val helpOutput: ShellResult,
        /** False: the recorder dies right after launch. */
        val started: Boolean = true,
        var size: Long = 4_000_000,
    ) : CommandExecutor, FileReceiver {
        val commands = mutableListOf<String>()
        var isAlive = false
        var justRead = ""

        override suspend fun execute(command: String): ShellResult {
            commands += command
            return when {
                command.startsWith("screenrecord --help") -> helpOutput
                command.contains("setsid") -> {
                    isAlive = started
                    ShellResult(0, "")
                }
                command.contains("kill -INT") && command.contains("stat -c") -> {
                    isAlive = false
                    ShellResult(0, "$size\n")
                }
                command.contains("kill -0") -> ShellResult(if (isAlive) 0 else 1, "")
                command.startsWith("cat ") -> ShellResult(0, "ERROR: unable to configure video encoder\n")
                else -> ShellResult(0, "")
            }
        }

        override suspend fun receive(
            path: String,
            destination: OutputStream,
            size: Long,
            cancelled: () -> Boolean,
            onReceived: (received: Long) -> Unit,
        ): ShellResult {
            justRead = path
            destination.write(byteArrayOf(1, 2, 3))
            onReceived(3)
            return ShellResult(0, "")
        }
    }

    @Test
    fun `on Android 14 the recorder starts detached and without a time limit`() = runTest {
        val tv = FakeTv(ShellResult(0, help14))

        val startResult = TvRecording(tv, tv).start()

        assertEquals(RecordingStart.Started(limitSeconds = null), startResult)
        val launch = tv.commands.single { it.contains("setsid") }
        assertTrue(launch, launch.contains("--time-limit 0"))
        assertTrue(launch, launch.contains(TvRecording.VIDEO))
        // A replayed command must not start a second recorder on the same file.
        assertTrue(launch, launch.contains("echo deja"))
        // Leftovers from a previous session are stopped and deleted first.
        assertTrue(tv.commands.indexOfFirst { it.startsWith("p=") && it.contains("rm -f") } < tv.commands.indexOf(launch))
    }

    @Test
    fun `before Android 14 recording is capped at three minutes`() = runTest {
        val tv = FakeTv(ShellResult(0, help11))

        assertEquals(RecordingStart.Started(limitSeconds = 180), TvRecording(tv, tv).start())
        assertTrue(tv.commands.single { it.contains("setsid") }.contains("--time-limit 180"))
    }

    @Test
    fun `without screenrecord nothing is started`() = runTest {
        val tv = FakeTv(ShellResult(127, "/system/bin/sh: screenrecord: inaccessible or not found"))

        val startResult = TvRecording(tv, tv).start()

        assertEquals(RecordingCause.UNAVAILABLE, (startResult as RecordingStart.Rejected).cause)
        assertFalse(tv.commands.any { it.contains("setsid") })
    }

    @Test
    fun `a recorder that dies right away reports why`() = runTest {
        val tv = FakeTv(ShellResult(0, help14), started = false)

        val startResult = TvRecording(tv, tv).start()

        assertEquals(
            RecordingStart.Rejected(RecordingCause.FAILED, "ERROR: unable to configure video encoder"),
            startResult,
        )
    }

    @Test
    fun `a lost connection is reported as such`() = runTest {
        val tv = FakeTv(ShellResult.unavailable("No TV connected."))

        assertEquals(
            RecordingStart.Rejected(RecordingCause.CONNECTION, "No TV connected."),
            TvRecording(tv, tv).start(),
        )
    }

    @Test
    fun `stopping sends SIGINT, then the video is copied and deleted`() = runTest {
        val tv = FakeTv(ShellResult(0, help14))
        val recording = TvRecording(tv, tv)
        recording.start()
        assertEquals(true, recording.isAlive())

        assertEquals(RecordingStop.Done(4_000_000), recording.stop())
        assertEquals(false, recording.isAlive())

        val incoming = ByteArrayOutputStream()
        assertTrue(recording.download(incoming, 4_000_000).succeeded)
        assertEquals(TvRecording.VIDEO, tv.justRead)
        assertEquals(3, incoming.size())

        recording.clean()
        assertTrue(tv.commands.last().startsWith("rm -f ${TvRecording.VIDEO}"))
    }

    @Test
    fun `a recording without a file is not copied`() = runTest {
        val tv = FakeTv(ShellResult(0, help14), size = 0)
        val recording = TvRecording(tv, tv)
        recording.start()

        assertEquals(RecordingCause.EMPTY, (recording.stop() as RecordingStop.Rejected).cause)
    }
}
