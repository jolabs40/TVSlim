package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.ResultatShell

/** A file found in a TV folder, with its path relative to that folder. */
data class FichierInventaire(val chemin: String, val taille: Long, val date: Long)

/** Recursive contents of a TV folder: subfolders (empty ones included) and files. */
data class Inventaire(val dossiers: List<String>, val fichiers: List<FichierInventaire>) {
    val taille: Long get() = fichiers.sumOf { it.taille }
}

internal sealed interface LectureInventaire {
    data class Lu(val inventaire: Inventaire) : LectureInventaire

    data object Protege : LectureInventaire

    data class Illisible(val refus: RefusLecture, val motif: String = "") : LectureInventaire
}

/**
 * Lists a folder recursively before it is copied or deleted, and builds the delete command.
 *
 * Uses `find -exec stat {} +` because Android's toybox `find` has no `-printf`. One line per entry,
 * `D|0|date|./path` or `F|size|date|./path`, path last so it may contain `|`. Symlinks are not followed.
 * Tested with toybox 0.8.9 (TCL) and 0.8.3 (Shield).
 */
internal object InventaireDossier {

    private const val CODE_INTROUVABLE = 2
    private const val CODE_REFUSE = 3
    private const val CODE_PROTEGE = 5

    /**
     * Storage roots never deleted wholesale: internal storage under its three names, each `/storage` volume
     * (USB drive, SD card), the apps' `Android` folder, and `/data/local/tmp`.
     */
    private const val RACINES = "/sdcard /sdcard/Android /storage/* /storage/emulated/* " +
        "/storage/emulated/0 /storage/self/primary /data/local/tmp"

    /**
     * Exits with [CODE_PROTEGE] if the quoted folder is one of [RACINES] or contains one (`/storage/emulated`
     * contains internal storage). Paths are compared after resolving links: `/sdcard`, `/storage/self/primary`
     * and `/storage/emulated/0` are the same folder. An `rm -rf` reaching a root would delete everything the
     * ADB shell is allowed to delete.
     */
    private fun garde(cite: String): String =
        "c=\$(readlink -f $cite); [ -n \"\$c\" ] || exit $CODE_INTROUVABLE; " +
            "for r in $RACINES; do r=\$(readlink -f \"\$r\") && " +
            "case \"\$r/\" in \"\${c%/}/\"*) exit $CODE_PROTEGE;; esac; done"

    /** [garde]: before a delete, rejects up front what [commandeSuppression] would refuse. */
    fun commande(chemin: String, garde: Boolean): String {
        val cite = citer(chemin)
        return (if (garde) garde(cite) + "; " else "") +
            "[ -d $cite ] || exit $CODE_INTROUVABLE; " +
            "cd $cite 2>/dev/null && ls -a >/dev/null 2>&1 || exit $CODE_REFUSE; " +
            "find . -type d -exec stat -c 'D|0|%Y|%n' {} + 2>/dev/null; " +
            "find . -type f -exec stat -c 'F|%s|%Y|%n' {} + 2>/dev/null; exit 0"
    }

    /**
     * `rm -f` and `rm -rf` are safe to replay after a disconnect (what is gone stays gone), so they go through
     * the regular command path. A link gets no `-r`: `rm` would not follow it anyway.
     */
    fun commandeSuppression(chemin: String, nature: NatureSuppression): String {
        val cite = citer(chemin)
        return when (nature) {
            NatureSuppression.DOSSIER -> garde(cite) + "; rm -rf $cite"
            NatureSuppression.FICHIER, NatureSuppression.LIEN -> "rm -f $cite"
        }
    }

    fun lire(reponse: ResultatShell): LectureInventaire = when (reponse.code) {
        0 -> LectureInventaire.Lu(inventaire(reponse.sortie))
        CODE_INTROUVABLE -> LectureInventaire.Illisible(RefusLecture.INTROUVABLE)
        CODE_REFUSE -> LectureInventaire.Illisible(RefusLecture.REFUSE)
        CODE_PROTEGE -> LectureInventaire.Protege
        else -> LectureInventaire.Illisible(RefusLecture.ECHEC, reponse.sortie.ifBlank { "Code de retour ${reponse.code}." })
    }

    fun suppression(nom: String, reponse: ResultatShell): SuppressionEntree = when {
        reponse.reussi -> SuppressionEntree(IssueSuppression.SUPPRIME, nom)
        reponse.code == CODE_PROTEGE -> SuppressionEntree(IssueSuppression.PROTEGE, nom)
        else -> SuppressionEntree(IssueSuppression.ECHEC, nom, reponse.sortie.ifBlank { "Code de retour ${reponse.code}." })
    }

    /** Parent folders first; the folder itself (`.`) is not included. */
    fun inventaire(sortie: String): Inventaire {
        val dossiers = mutableListOf<String>()
        val fichiers = mutableListOf<FichierInventaire>()
        sortie.lines().forEach { ligne ->
            val champs = ligne.split('|', limit = 4)
            if (champs.size < 4 || !champs[3].startsWith("./")) return@forEach
            val chemin = champs[3].removePrefix("./")
            if (chemin.isEmpty()) return@forEach
            when (champs[0]) {
                "D" -> dossiers += chemin
                "F" -> fichiers += FichierInventaire(
                    chemin = chemin,
                    taille = champs[1].toLongOrNull() ?: 0L,
                    date = (champs[2].toLongOrNull() ?: 0L) * 1_000L,
                )
            }
        }
        return Inventaire(dossiers.sortedBy { chemin -> chemin.count { it == '/' } }, fichiers)
    }
}
