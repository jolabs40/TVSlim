package net.jolabs40.tvslim.ecran

import kotlinx.coroutines.delay
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ResultatShell
import java.io.OutputStream

/** Why a recording did not start or produced nothing; each app localizes the message. */
enum class CauseEnregistrement {
    /** No session, or the connection dropped. */
    CONNEXION,

    /** The TV has no `screenrecord`. */
    INDISPONIBLE,

    /** `screenrecord` exited right after starting (encoder refused, protected screen...). */
    ECHEC,

    /** Stopped, but no file or an empty one. */
    VIDE,
}

sealed interface Demarrage {
    /** [limiteS]: maximum duration enforced before Android 14, or `null` when there is none. */
    data class Lance(val limiteS: Int?) : Demarrage

    data class Refuse(val cause: CauseEnregistrement, val detail: String) : Demarrage
}

sealed interface Arret {
    data class Termine(val taille: Long) : Arret

    data class Refuse(val cause: CauseEnregistrement, val detail: String) : Arret
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
class EnregistrementTv(
    private val executeur: ExecuteurCommande,
    private val recepteur: RecepteurFichiers,
) {

    suspend fun demarrer(): Demarrage {
        val aide = executeur.executer("screenrecord --help 2>&1")
        if (aide.code < 0) return Demarrage.Refuse(CauseEnregistrement.CONNEXION, aide.sortie)
        if (!aide.sortie.contains("screenrecord", ignoreCase = true) || aide.sortie.contains("not found")) {
            return Demarrage.Refuse(CauseEnregistrement.INDISPONIBLE, aide.sortie.trim())
        }
        // "Set to 0 to remove the time limit" appears from Android 14; before that, 180 s at most.
        val limiteS = if (aide.sortie.contains("Set to 0")) null else LIMITE_ANCIENNE_S

        // Stops and deletes a recording left by a previous session (TV Slim force-closed).
        val nettoyage = executeur.executer("$ARRETER_PRECEDENT; rm -f $VIDEO $PID $JOURNAL")
        if (nettoyage.code < 0) return Demarrage.Refuse(CauseEnregistrement.CONNEXION, nettoyage.sortie)

        // No-op if the recorder is already running, so a command replayed after a disconnect does not start a
        // second one on the same file.
        val lancement = executeur.executer(
            "p=\$(cat $PID 2>/dev/null); if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; then echo deja; else " +
                "setsid sh -c 'echo \$\$ > $PID; exec screenrecord --time-limit ${limiteS ?: 0} --bit-rate $DEBIT $VIDEO' " +
                "> $JOURNAL 2>&1 < /dev/null & fi",
        )
        if (lancement.code < 0) return Demarrage.Refuse(CauseEnregistrement.CONNEXION, lancement.sortie)

        // An encoder that rejects the resolution, or a protected screen, stops it within a second.
        delay(ATTENTE_DEMARRAGE_MS)
        return when (vivant()) {
            true -> Demarrage.Lance(limiteS)
            null -> Demarrage.Refuse(CauseEnregistrement.CONNEXION, "")
            false -> Demarrage.Refuse(CauseEnregistrement.ECHEC, journal())
        }
    }

    /** Whether the recorder is still running; `null` when the TV does not answer. */
    suspend fun vivant(): Boolean? {
        val reponse = executeur.executer("p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null")
        return when {
            reponse.code < 0 -> null
            else -> reponse.code == 0
        }
    }

    /**
     * Stops the recorder with `SIGINT`, waits for it to finish writing, and returns the video size. If it
     * already stopped (time limit reached), only the size is read.
     */
    suspend fun arreter(): Arret {
        val reponse = executeur.executer(
            "p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -INT \"\$p\" 2>/dev/null; " +
                "i=0; while [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null && [ \$i -lt 50 ]; do sleep 0.2; i=\$((i+1)); done; " +
                "stat -c %s $VIDEO 2>/dev/null || echo 0",
        )
        if (reponse.code < 0) return Arret.Refuse(CauseEnregistrement.CONNEXION, reponse.sortie)
        val taille = reponse.sortie.lines().lastOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: 0L
        return if (taille > 0) Arret.Termine(taille) else Arret.Refuse(CauseEnregistrement.VIDE, journal())
    }

    /** Copies the video from the TV into [destination]; the caller closes it. */
    suspend fun rapatrier(
        destination: OutputStream,
        taille: Long,
        annule: () -> Boolean = { false },
        surRecu: (Long) -> Unit = {},
    ): ResultatShell = recepteur.recevoir(VIDEO, destination, taille, annule, surRecu)

    /** Deletes the video and its tracking files from the TV once the copy is done. */
    suspend fun nettoyer(): ResultatShell = executeur.executer("rm -f $VIDEO $PID $JOURNAL")

    private suspend fun journal(): String =
        executeur.executer("cat $JOURNAL 2>/dev/null").sortie.trim().lines().takeLast(3).joinToString(" ")

    companion object {
        /** In the ADB shell's folder: invisible to TV apps and never media-scanned. */
        const val VIDEO = "/data/local/tmp/tvslim-enregistrement.mp4"
        const val PID = "/data/local/tmp/tvslim-enregistrement.pid"
        const val JOURNAL = "/data/local/tmp/tvslim-enregistrement.log"

        /** 8 Mbit/s, same as scrcpy: at most 60 MB per minute, much less on a static screen. */
        const val DEBIT = "8M"
        const val LIMITE_ANCIENNE_S = 180
        const val ATTENTE_DEMARRAGE_MS = 1_000L

        private const val ARRETER_PRECEDENT =
            "p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -INT \"\$p\" 2>/dev/null && sleep 1"
    }
}
