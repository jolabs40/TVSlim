package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.ResultatShell

/** Un fichier trouvé dans un dossier du téléviseur, son chemin relatif à ce dossier. */
data class FichierInventaire(val chemin: String, val taille: Long, val date: Long)

/** Le contenu d'un dossier du téléviseur, à toute profondeur : ses sous-dossiers, vides compris, et ses fichiers. */
data class Inventaire(val dossiers: List<String>, val fichiers: List<FichierInventaire>) {
    val taille: Long get() = fichiers.sumOf { it.taille }
}

internal sealed interface LectureInventaire {
    data class Lu(val inventaire: Inventaire) : LectureInventaire

    data object Protege : LectureInventaire

    data class Illisible(val refus: RefusLecture, val motif: String = "") : LectureInventaire
}

/**
 * Ce que contient un dossier, avant de le copier ou de l'effacer — et la commande qui l'efface.
 *
 * `find -exec stat {} +` plutôt que `find -printf`, que la toybox d'Android refuse : une ligne
 * `D|0|date|./chemin` par dossier, `F|taille|date|./chemin` par fichier, le chemin en dernier pour qu'il garde
 * ses `|`. Ni l'un ni l'autre `find` ne suit les liens : un lien n'est ni copié, ni parcouru. Éprouvé sur la TCL
 * (toybox 0.8.9) et la Shield (0.8.3), le 2026-10-04.
 */
internal object InventaireDossier {

    private const val CODE_INTROUVABLE = 2
    private const val CODE_REFUSE = 3
    private const val CODE_PROTEGE = 5

    /**
     * Les stockages qu'on n'efface jamais d'un bloc : le stockage interne sous ses trois noms, chaque volume de
     * `/storage` — clé USB, carte SD —, le dossier `Android` des applications, et `/data/local/tmp`.
     */
    private const val RACINES = "/sdcard /sdcard/Android /storage/* /storage/emulated/* " +
        "/storage/emulated/0 /storage/self/primary /data/local/tmp"

    /**
     * Sort en [CODE_PROTEGE] si le dossier cité est l'un des [RACINES], ou en contient un — `/storage/emulated`
     * contient le stockage interne. Les chemins sont comparés une fois les liens résolus : `/sdcard`,
     * `/storage/self/primary` et `/storage/emulated/0` sont le même dossier. Un `rm -rf` arrivé à la racine
     * effacerait tout ce que le shell d'ADB a le droit d'effacer.
     */
    private fun garde(cite: String): String =
        "c=\$(readlink -f $cite); [ -n \"\$c\" ] || exit $CODE_INTROUVABLE; " +
            "for r in $RACINES; do r=\$(readlink -f \"\$r\") && " +
            "case \"\$r/\" in \"\${c%/}/\"*) exit $CODE_PROTEGE;; esac; done"

    /** [garde] : avant une suppression, refuser d'emblée ce que [commandeSuppression] refuserait. */
    fun commande(chemin: String, garde: Boolean): String {
        val cite = citer(chemin)
        return (if (garde) garde(cite) + "; " else "") +
            "[ -d $cite ] || exit $CODE_INTROUVABLE; " +
            "cd $cite 2>/dev/null && ls -a >/dev/null 2>&1 || exit $CODE_REFUSE; " +
            "find . -type d -exec stat -c 'D|0|%Y|%n' {} + 2>/dev/null; " +
            "find . -type f -exec stat -c 'F|%s|%Y|%n' {} + 2>/dev/null; exit 0"
    }

    /**
     * `rm -f` et `rm -rf` se rejouent sans risque après une rupture — ce qui est parti ne se retrouve pas —, et
     * passent donc par le chemin ordinaire. Un lien perd son `-r` : `rm` ne le suit pas, mais rien ne sert de
     * l'y inviter.
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

    /** Les dossiers parents d'abord ; le dossier lui-même (`.`) n'y figure pas. */
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
