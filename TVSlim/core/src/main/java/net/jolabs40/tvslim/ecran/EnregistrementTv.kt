package net.jolabs40.tvslim.ecran

import kotlinx.coroutines.delay
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ResultatShell
import java.io.OutputStream

/** Pourquoi un enregistrement n'a pas démarré, ou n'a rien rendu — chaque application le dit dans sa langue. */
enum class CauseEnregistrement {
    /** Aucune session, ou la connexion a lâché. */
    CONNEXION,

    /** Le téléviseur n'a pas `screenrecord`. */
    INDISPONIBLE,

    /** `screenrecord` s'est arrêté aussitôt lancé : encodeur refusé, écran protégé… */
    ECHEC,

    /** Arrêté, mais sans fichier — ou un fichier vide. */
    VIDE,
}

sealed interface Demarrage {
    /** [limiteS] : la durée maximale imposée par un Android d'avant la 14 ; `null` quand il n'y en a pas. */
    data class Lance(val limiteS: Int?) : Demarrage

    data class Refuse(val cause: CauseEnregistrement, val detail: String) : Demarrage
}

sealed interface Arret {
    data class Termine(val taille: Long) : Arret

    data class Refuse(val cause: CauseEnregistrement, val detail: String) : Arret
}

/**
 * La vidéo de l'écran, enregistrée **sur le téléviseur** par son propre `screenrecord`, puis copiée.
 *
 * Choisi plutôt que scrcpy (décision de l'utilisateur, 2026-10-04) : ni logiciel tiers, ni seconde autorisation
 * ADB, rien qui transite pendant l'enregistrement. Le prix : **pas de son** — `screenrecord` n'en capture
 * jamais —, une copie à attendre à la fin, et trois minutes au plus avant Android 14.
 *
 * L'enregistreur est lancé **détaché** (`setsid`) : il survit à la commande qui l'a lancé, et même à une
 * session ADB perdue en route. Il s'arrête sur `SIGINT`, qui lui fait écrire l'index du MP4 — un `SIGKILL`
 * laisserait un fichier illisible.
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
        // « Set to 0 to remove the time limit » : Android 14 et plus. Avant, 180 s au plus.
        val limiteS = if (aide.sortie.contains("Set to 0")) null else LIMITE_ANCIENNE_S

        // Un enregistrement laissé par une session précédente — TV Slim fermé de force — est arrêté et effacé.
        val nettoyage = executeur.executer("$ARRETER_PRECEDENT; rm -f $VIDEO $PID $JOURNAL")
        if (nettoyage.code < 0) return Demarrage.Refuse(CauseEnregistrement.CONNEXION, nettoyage.sortie)

        // Ne lance rien si l'enregistreur tourne déjà : une commande rejouée après une rupture n'en démarre pas un
        // second sur le même fichier.
        val lancement = executeur.executer(
            "p=\$(cat $PID 2>/dev/null); if [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null; then echo deja; else " +
                "setsid sh -c 'echo \$\$ > $PID; exec screenrecord --time-limit ${limiteS ?: 0} --bit-rate $DEBIT $VIDEO' " +
                "> $JOURNAL 2>&1 < /dev/null & fi",
        )
        if (lancement.code < 0) return Demarrage.Refuse(CauseEnregistrement.CONNEXION, lancement.sortie)

        // Un encodeur qui refuse la définition, ou un écran protégé, l'arrête dans la seconde.
        delay(ATTENTE_DEMARRAGE_MS)
        return when (vivant()) {
            true -> Demarrage.Lance(limiteS)
            null -> Demarrage.Refuse(CauseEnregistrement.CONNEXION, "")
            false -> Demarrage.Refuse(CauseEnregistrement.ECHEC, journal())
        }
    }

    /** L'enregistreur tourne-t-il encore ? `null` quand le téléviseur ne répond pas. */
    suspend fun vivant(): Boolean? {
        val reponse = executeur.executer("p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -0 \"\$p\" 2>/dev/null")
        return when {
            reponse.code < 0 -> null
            else -> reponse.code == 0
        }
    }

    /**
     * Arrête l'enregistreur par `SIGINT`, attend qu'il ait fini d'écrire, et rend la taille de la vidéo. Déjà
     * arrêté — limite atteinte —, il ne reste qu'à lire la taille.
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

    /** Copie la vidéo du téléviseur dans [destination], que referme l'appelant. */
    suspend fun rapatrier(
        destination: OutputStream,
        taille: Long,
        annule: () -> Boolean = { false },
        surRecu: (Long) -> Unit = {},
    ): ResultatShell = recepteur.recevoir(VIDEO, destination, taille, annule, surRecu)

    /** Efface la vidéo et ses fichiers de suivi du téléviseur, une fois la copie arrivée. */
    suspend fun nettoyer(): ResultatShell = executeur.executer("rm -f $VIDEO $PID $JOURNAL")

    private suspend fun journal(): String =
        executeur.executer("cat $JOURNAL 2>/dev/null").sortie.trim().lines().takeLast(3).joinToString(" ")

    companion object {
        /** Dans le dossier du shell d'ADB : invisible des applications du téléviseur, et jamais scanné. */
        const val VIDEO = "/data/local/tmp/tvslim-enregistrement.mp4"
        const val PID = "/data/local/tmp/tvslim-enregistrement.pid"
        const val JOURNAL = "/data/local/tmp/tvslim-enregistrement.log"

        /** 8 Mbit/s, le débit de scrcpy : 60 Mo par minute au plus, bien moins sur un écran calme. */
        const val DEBIT = "8M"
        const val LIMITE_ANCIENNE_S = 180
        const val ATTENTE_DEMARRAGE_MS = 1_000L

        private const val ARRETER_PRECEDENT =
            "p=\$(cat $PID 2>/dev/null); [ -n \"\$p\" ] && kill -INT \"\$p\" 2>/dev/null && sleep 1"
    }
}
