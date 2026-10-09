package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.ResultatShell

enum class NatureEntree { DOSSIER, FICHIER, AUTRE }

/** An entry of a TV folder, as described by `stat`. */
data class EntreeDistante(
    val nom: String,
    /** For a symlink, the type of its target, so `/sdcard` browses like a folder. */
    val nature: NatureEntree,
    /** In bytes; meaningless for a folder. */
    val taille: Long,
    /** Last modified time in milliseconds. */
    val date: Long,
    val lien: Boolean = false,
) {
    val dossier: Boolean get() = nature == NatureEntree.DOSSIER
}

/** Result of listing a folder. */
sealed interface LectureDossier {
    val chemin: String

    data class Lue(override val chemin: String, val entrees: List<EntreeDistante>) : LectureDossier

    data class Introuvable(override val chemin: String) : LectureDossier

    /** The folder exists but Android does not let the ADB shell open it (usually `/data`). */
    data class Refusee(override val chemin: String) : LectureDossier

    /** [motif]: the raw answer from the connection or the TV. */
    data class Echouee(override val chemin: String, val motif: String) : LectureDossier
}

enum class NatureRaccourci { INTERNE, TELECHARGEMENTS, FILMS, MUSIQUE, IMAGES, VOLUME, TEMPORAIRE, RACINE }

/** A one-click folder shortcut. Each app localizes its label; a volume shows its own name. */
data class Raccourci(val nature: NatureRaccourci, val chemin: String) {
    val nom: String get() = CheminDistant.nom(chemin)

    companion object {
        /**
         * `/sdcard` rather than `/storage/emulated/0`: it is the path tutorials give, and leads to the same
         * place. `/data/local/tmp` is the only folder outside shared storage where the shell can write.
         */
        fun avecVolumes(volumes: List<String>): List<Raccourci> = buildList {
            add(Raccourci(NatureRaccourci.INTERNE, DOSSIER_DE_DEPART))
            add(Raccourci(NatureRaccourci.TELECHARGEMENTS, "$DOSSIER_DE_DEPART/Download"))
            add(Raccourci(NatureRaccourci.FILMS, "$DOSSIER_DE_DEPART/Movies"))
            add(Raccourci(NatureRaccourci.MUSIQUE, "$DOSSIER_DE_DEPART/Music"))
            add(Raccourci(NatureRaccourci.IMAGES, "$DOSSIER_DE_DEPART/Pictures"))
            volumes.forEach { add(Raccourci(NatureRaccourci.VOLUME, "/storage/$it")) }
            add(Raccourci(NatureRaccourci.TEMPORAIRE, "/data/local/tmp"))
            add(Raccourci(NatureRaccourci.RACINE, CheminDistant.RACINE))
        }
    }
}

/** Shared storage as apps see it; the starting folder. */
const val DOSSIER_DE_DEPART = "/sdcard"

/**
 * Builds the folder listing command and parses its output.
 *
 * `stat -c` rather than `ls -l`, whose columns do not show where the date ends and a name with spaces begins.
 * `find -printf` alone would do, but Android's toybox lacks it (tested with toybox 0.8.9 on the TCL and 0.8.3
 * on the Shield).
 *
 * One `E|mode|size|date|name` line per entry, name last so it may contain `|`, then one `D|name` line per link
 * that leads to a folder: `[ -d ]` follows the link without another `stat`.
 */
internal object LecteurDossier {

    /** The folder does not exist, or is not a folder. */
    private const val CODE_INTROUVABLE = 2

    /** It exists but cannot be entered or listed. */
    private const val CODE_REFUSE = 3

    private const val MASQUE_TYPE = 0xF000
    private const val TYPE_DOSSIER = 0x4000
    private const val TYPE_FICHIER = 0x8000
    private const val TYPE_LIEN = 0xA000

    /**
     * The TV shell (mksh) does not expand `.*` to `.` or `..`. A pattern with no match stays literal and
     * `stat` complains on stderr, which is discarded.
     */
    fun commande(chemin: String): String {
        val cite = citer(chemin)
        return "[ -d $cite ] || exit $CODE_INTROUVABLE; " +
            "cd $cite 2>/dev/null && ls -a >/dev/null 2>&1 || exit $CODE_REFUSE; " +
            "stat -c 'E|%f|%s|%Y|%n' .* * 2>/dev/null; " +
            "for f in .* *; do [ -L \"\$f\" ] && [ -d \"\$f\" ] && echo \"D|\$f\"; done; exit 0"
    }

    fun lire(chemin: String, reponse: ResultatShell): LectureDossier = when (reponse.code) {
        0 -> LectureDossier.Lue(chemin, entrees(reponse.sortie))
        CODE_INTROUVABLE -> LectureDossier.Introuvable(chemin)
        CODE_REFUSE -> LectureDossier.Refusee(chemin)
        else -> LectureDossier.Echouee(chemin, reponse.sortie.ifBlank { "Code de retour ${reponse.code}." })
    }

    /** Folders first, then files, each sorted case-insensitively. */
    fun entrees(sortie: String): List<EntreeDistante> {
        val lignes = sortie.lines()
        val liensVersDossier = lignes.filter { it.startsWith("D|") }.map { it.substring(2) }.toSet()
        return lignes
            .filter { it.startsWith("E|") }
            .mapNotNull { entree(it, liensVersDossier) }
            .distinctBy { it.nom }
            .sortedWith(compareBy<EntreeDistante> { !it.dossier }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.nom })
    }

    private fun entree(ligne: String, liensVersDossier: Set<String>): EntreeDistante? {
        val champs = ligne.split('|', limit = 5)
        if (champs.size < 5) return null
        val nom = champs[4]
        if (nom.isEmpty() || nom == "." || nom == "..") return null
        val type = (champs[1].toIntOrNull(16) ?: return null) and MASQUE_TYPE
        val lien = type == TYPE_LIEN
        return EntreeDistante(
            nom = nom,
            nature = when {
                type == TYPE_DOSSIER || (lien && nom in liensVersDossier) -> NatureEntree.DOSSIER
                type == TYPE_FICHIER || lien -> NatureEntree.FICHIER
                else -> NatureEntree.AUTRE
            },
            taille = champs[2].toLongOrNull() ?: 0L,
            date = (champs[3].toLongOrNull() ?: 0L) * 1_000L,
            lien = lien,
        )
    }

    /** Removable volumes (USB drive, SD card) are mounted under `/storage`, next to internal storage. */
    const val COMMANDE_VOLUMES = "ls /storage"

    fun volumes(sortie: String): List<String> =
        sortie.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "emulated" && it != "self" && CheminDistant.nomValide(it) }
            .sorted()
}
