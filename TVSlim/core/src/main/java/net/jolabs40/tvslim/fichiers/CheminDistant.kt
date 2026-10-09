package net.jolabs40.tvslim.fichiers

/**
 * TV-side paths: Unix style, `/`-separated, always absolute here.
 *
 * They end up in shell commands, and file names come from the user (a folder dragged from Explorer, a
 * document picked on the phone), so they may contain a quote or a semicolon. [citer] passes them intact.
 */
object CheminDistant {

    const val RACINE = "/"

    /** Maximum name length on Android file systems, in bytes rather than characters. */
    private const val NOM_MAX_OCTETS = 255

    /** Normalizes a path: no `//`, `.`, `..` or trailing `/`. A relative path starts from the root. */
    fun normaliser(chemin: String): String {
        val etapes = mutableListOf<String>()
        chemin.split('/').forEach { etape ->
            when (etape) {
                "", "." -> Unit
                ".." -> if (etapes.isNotEmpty()) etapes.removeAt(etapes.lastIndex)
                else -> etapes += etape
            }
        }
        return etapes.joinToString(separator = "/", prefix = "/")
    }

    fun joindre(dossier: String, nom: String): String = if (dossier == RACINE) "/$nom" else "$dossier/$nom"

    /** Parent folder of [chemin]; null for the root. */
    fun parent(chemin: String): String? =
        if (chemin == RACINE) null else chemin.substringBeforeLast('/').ifEmpty { RACINE }

    fun nom(chemin: String): String = chemin.substringAfterLast('/')

    /** Breadcrumb from the root to [chemin]: each step with its name and full path. */
    fun etapes(chemin: String): List<EtapeChemin> {
        val etapes = mutableListOf(EtapeChemin(RACINE, RACINE))
        var courant = RACINE
        normaliser(chemin).split('/').filter { it.isNotEmpty() }.forEach { nom ->
            courant = joindre(courant, nom)
            etapes += EtapeChemin(nom, courant)
        }
        return etapes
    }

    /**
     * Whether a name may be created on the TV: not empty, not `.` or `..`, no `/` or control character, and
     * at most 255 bytes.
     *
     * Android accepts control characters, but a newline would break the line-based folder listing and make
     * the file unreadable from TV Slim.
     */
    fun nomValide(nom: String): Boolean =
        nom.isNotEmpty() &&
            nom != "." &&
            nom != ".." &&
            nom.none { it == '/' || it.code < 0x20 || it.code == 0x7F } &&
            nom.toByteArray(Charsets.UTF_8).size <= NOM_MAX_OCTETS
}

data class EtapeChemin(val nom: String, val chemin: String)

/** Single-quotes [texte] for the shell. Only the quote itself needs handling: close, escape, reopen. */
fun citer(texte: String): String = "'" + texte.replace("'", "'\\''") + "'"
