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
 * Un miroir scrcpy lancé par TV Slim : sa fenêtre, ce qu'il écrit, et la façon de l'arrêter.
 *
 * Arrêter, c'est **fermer sa fenêtre** comme on le ferait à la souris (`WM_CLOSE`) : scrcpy termine alors
 * proprement, et rend la main à son `adb`. Tuer le processus n'est que le dernier recours, quand la fenêtre ne
 * s'est jamais ouverte.
 *
 * Lancé comme enfant de TV Slim, il vit dans le même *job* que lui (voir `CLAUDE.md`, mises à jour) : fermer
 * TV Slim le ferme aussi.
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

    /** Les dernières lignes écrites par scrcpy : de quoi dire pourquoi il s'est arrêté. */
    val sortie: List<String> get() = synchronized(lignes) { lignes.toList() }

    val vivante: Boolean get() = processus.isAlive

    suspend fun attendre(): Int = withContext(Dispatchers.IO) { processus.waitFor() }

    /** Ferme la fenêtre, attend que scrcpy ait fini d'écrire, et ne tue le processus qu'en dernier recours. */
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

        /** `WM_CLOSE` à chaque fenêtre de premier niveau du processus : celle de scrcpy, titrée par TV Slim. */
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
