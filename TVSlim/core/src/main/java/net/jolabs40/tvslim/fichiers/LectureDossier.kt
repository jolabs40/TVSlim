package net.jolabs40.tvslim.fichiers

import net.jolabs40.tvslim.shell.ResultatShell

enum class NatureEntree { DOSSIER, FICHIER, AUTRE }

/** Une entrée d'un dossier du téléviseur, telle que `stat` la décrit. */
data class EntreeDistante(
    val nom: String,
    /** Pour un lien symbolique, la nature de sa cible : `/sdcard` se parcourt comme un dossier. */
    val nature: NatureEntree,
    /** En octets ; sans signification pour un dossier. */
    val taille: Long,
    /** Dernière modification, en millisecondes. */
    val date: Long,
    val lien: Boolean = false,
) {
    val dossier: Boolean get() = nature == NatureEntree.DOSSIER
}

/** Ce que la lecture d'un dossier a donné. */
sealed interface LectureDossier {
    val chemin: String

    data class Lue(override val chemin: String, val entrees: List<EntreeDistante>) : LectureDossier

    data class Introuvable(override val chemin: String) : LectureDossier

    /** Le dossier existe, mais Android ne laisse pas le shell d'ADB l'ouvrir : `/data`, le plus souvent. */
    data class Refusee(override val chemin: String) : LectureDossier

    /** [motif] : ce qu'ont répondu la connexion ou le téléviseur, tel quel. */
    data class Echouee(override val chemin: String, val motif: String) : LectureDossier
}

enum class NatureRaccourci { INTERNE, TELECHARGEMENTS, FILMS, MUSIQUE, IMAGES, VOLUME, TEMPORAIRE, RACINE }

/** Un dossier qu'on atteint d'un clic. Chaque application le nomme dans sa langue ; un volume, par son nom. */
data class Raccourci(val nature: NatureRaccourci, val chemin: String) {
    val nom: String get() = CheminDistant.nom(chemin)

    companion object {
        /**
         * `/sdcard` plutôt que `/storage/emulated/0` : c'est le chemin que donnent les tutoriels, et il mène au
         * même endroit. `/data/local/tmp` est le seul dossier hors du stockage partagé où le shell écrive.
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

/** Le stockage partagé : celui que voient les applications, et le dossier où l'on arrive. */
const val DOSSIER_DE_DEPART = "/sdcard"

/**
 * La commande qui lit un dossier, et la lecture de sa réponse.
 *
 * `stat -c` plutôt que `ls -l` : les colonnes de `ls` ne disent pas où finit la date et où commence un nom qui
 * contient des espaces. `find -printf` aurait suffi seul, mais la toybox d'Android le refuse — éprouvé sur la
 * TCL (toybox 0.8.9) et la Shield (0.8.3), le 2026-10-03.
 *
 * Une ligne `E|mode|taille|date|nom` par entrée, le nom en dernier pour qu'il garde ses `|`, puis une ligne
 * `D|nom` par lien qui mène à un dossier : `[ -d ]` suit le lien sans lancer un `stat` de plus.
 */
internal object LecteurDossier {

    /** Le dossier n'existe pas, ou n'en est pas un. */
    private const val CODE_INTROUVABLE = 2

    /** Il existe, mais on ne peut ni y entrer ni l'énumérer. */
    private const val CODE_REFUSE = 3

    private const val MASQUE_TYPE = 0xF000
    private const val TYPE_DOSSIER = 0x4000
    private const val TYPE_FICHIER = 0x8000
    private const val TYPE_LIEN = 0xA000

    /**
     * Le shell du téléviseur (mksh) n'étend pas `.*` en `.` ni `..` ; un motif qui ne trouve rien reste tel
     * quel, et `stat` s'en plaint sur une sortie d'erreur qu'on jette.
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

    /** Les dossiers d'abord, puis les fichiers, chacun par ordre alphabétique sans égard à la casse. */
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

    /** Les volumes amovibles — clé USB, carte SD — se montent sous `/storage`, à côté du stockage interne. */
    const val COMMANDE_VOLUMES = "ls /storage"

    fun volumes(sortie: String): List<String> =
        sortie.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "emulated" && it != "self" && CheminDistant.nomValide(it) }
            .sorted()
}
