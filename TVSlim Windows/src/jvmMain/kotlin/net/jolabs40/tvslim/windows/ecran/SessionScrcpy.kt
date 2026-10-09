package net.jolabs40.tvslim.windows.ecran

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.windows.outils.Traces
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
class SessionScrcpy private constructor(private val processus: Process) {

    private val lignes = mutableListOf<String>()

    init {
        thread(isDaemon = true, name = "scrcpy-sortie") {
            runCatching {
                processus.inputStream.bufferedReader().forEachLine { ligne ->
                    synchronized(lignes) {
                        lignes.add(ligne)
                        if (lignes.size > MAX_LIGNES) lignes.removeAt(0)
                    }
                }
            }
        }
    }

    /** Last lines printed by scrcpy, to explain why it stopped. */
    val sortie: List<String> get() = synchronized(lignes) { lignes.toList() }

    val vivante: Boolean get() = processus.isAlive

    suspend fun attendre(): Int = withContext(Dispatchers.IO) { processus.waitFor() }

    /** Closes the window, waits for scrcpy to exit, and kills it only after [attenteMs]. */
    fun arreter(attenteMs: Long = ATTENTE_ARRET_MS) {
        if (!processus.isAlive) return
        fermerFenetres(processus.pid())
        if (!processus.waitFor(attenteMs, TimeUnit.MILLISECONDS)) {
            Traces.avertir(TAG, "scrcpy ne s'est pas fermé : arrêt forcé")
            processus.destroyForcibly()
        }
    }

    companion object {
        private const val TAG = "Scrcpy"
        private const val MAX_LIGNES = 40
        const val ATTENTE_ARRET_MS = 8_000L

        fun lancer(exe: File, arguments: List<String>): SessionScrcpy {
            Traces.info(TAG, "Lancement du miroir scrcpy")
            val processus = ProcessBuilder(listOf(exe.absolutePath) + arguments)
                .directory(exe.parentFile)
                .redirectErrorStream(true)
                .start()
            return SessionScrcpy(processus)
        }

        /** Posts `WM_CLOSE` to every top-level window of the process. */
        private fun fermerFenetres(pid: Long) {
            runCatching {
                User32.INSTANCE.EnumWindows(
                    { fenetre, _ ->
                        val proprietaire = IntByReference()
                        User32.INSTANCE.GetWindowThreadProcessId(fenetre, proprietaire)
                        if (proprietaire.value.toLong() == pid) {
                            User32.INSTANCE.PostMessage(fenetre, WinUser.WM_CLOSE, WinDef.WPARAM(0), WinDef.LPARAM(0))
                        }
                        true
                    },
                    null,
                )
            }.onFailure { Traces.avertir(TAG, "Fenêtre de scrcpy non fermée", it) }
        }
    }
}
