package net.jolabs40.tvslim.windows.screen

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.windows.tools.AppLog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A scrcpy mirror process started by TV Slim: its output and how to stop it.
 *
 * Stopping sends `WM_CLOSE` to its window so scrcpy exits cleanly and releases its `adb`. Killing the process is
 * the last resort, for when the window never opened.
 *
 * As a child process it lives in the jpackage launcher's job object, so it dies when TV Slim exits.
 */
class ScrcpySession private constructor(private val process: Process) {

    private val lines = mutableListOf<String>()

    init {
        thread(isDaemon = true, name = "scrcpy-sortie") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(lines) {
                        lines.add(line)
                        if (lines.size > MAX_LINES) lines.removeAt(0)
                    }
                }
            }
        }
    }

    /** Last lines printed by scrcpy, to explain why it stopped. */
    val output: List<String> get() = synchronized(lines) { lines.toList() }

    val alive: Boolean get() = process.isAlive

    suspend fun waitFor(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    /** Closes the window, waits for scrcpy to exit, and kills it only after [waitMs]. */
    fun stop(waitMs: Long = STOP_WAIT_MS) {
        if (!process.isAlive) return
        closeWindows(process.pid())
        if (!process.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
            AppLog.warn(TAG, "scrcpy ne s'est pas fermé : arrêt forcé")
            process.destroyForcibly()
        }
    }

    companion object {
        private const val TAG = "Scrcpy"
        private const val MAX_LINES = 40
        const val STOP_WAIT_MS = 8_000L

        fun start(exe: File, arguments: List<String>): ScrcpySession {
            AppLog.info(TAG, "Lancement du miroir scrcpy")
            val process = ProcessBuilder(listOf(exe.absolutePath) + arguments)
                .directory(exe.parentFile)
                .redirectErrorStream(true)
                .start()
            return ScrcpySession(process)
        }

        /** Posts `WM_CLOSE` to every top-level window of the process. */
        private fun closeWindows(pid: Long) {
            runCatching {
                User32.INSTANCE.EnumWindows(
                    { window, _ ->
                        val owner = IntByReference()
                        User32.INSTANCE.GetWindowThreadProcessId(window, owner)
                        if (owner.value.toLong() == pid) {
                            User32.INSTANCE.PostMessage(window, WinUser.WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0))
                        }
                        true
                    },
                    null,
                )
            }.onFailure { AppLog.warn(TAG, "Fenêtre de scrcpy non fermée", it) }
        }
    }
}
