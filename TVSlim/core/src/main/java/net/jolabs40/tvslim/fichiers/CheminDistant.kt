package net.jolabs40.tvslim.fichiers

/**
 * Les chemins du téléviseur : à la façon d'Unix, séparés par `/`, et toujours absolus ici.
 *
 * Ils partent dans des commandes shell, et un nom de fichier vient de la personne — d'un dossier glissé
 * depuis l'Explorateur, d'un document choisi sur le téléphone. Rien ne l'empêche de porter une apostrophe ou
 * un point-virgule : [citer] l'y fait entrer intact.
 */
object CheminDistant {

    const val RACINE = "/"

    /** La limite d'un nom sur les systèmes de fichiers d'Android, en octets et non en caractères. */
    private const val NOM_MAX_OCTETS = 255

    /** Ramène un chemin à sa forme simple : ni `//`, ni `.`, ni `..`, ni `/` final. Un chemin relatif part de la racine. */
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

    /** Le dossier qui contient [chemin] ; la racine n'en a pas. */
    fun parent(chemin: String): String? =
        if (chemin == RACINE) null else chemin.substringBeforeLast('/').ifEmpty { RACINE }

    fun nom(chemin: String): String = chemin.substringAfterLast('/')

    /** Le fil d'Ariane, de la racine à [chemin] : chaque étape avec son nom et son chemin complet. */
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
     * Un nom qu'on accepte de créer sur le téléviseur : ni vide, ni `.` ou `..`, sans `/` ni caractère de
     * contrôle, et 255 octets au plus.
     *
     * Android prendrait un caractère de contrôle, mais un saut de ligne casserait la lecture des dossiers, qui
     * va ligne par ligne : le fichier deviendrait illisible depuis TV Slim.
     */
    fun nomValide(nom: String): Boolean =
        nom.isNotEmpty() &&
            nom != "." &&
            nom != ".." &&
            nom.none { it == '/' || it.code < 0x20 || it.code == 0x7F } &&
            nom.toByteArray(Charsets.UTF_8).size <= NOM_MAX_OCTETS
}

data class EtapeChemin(val nom: String, val chemin: String)

/** Entre apostrophes, pour le shell : la seule à traiter est l'apostrophe elle-même, qu'on ferme puis rouvre. */
fun citer(texte: String): String = "'" + texte.replace("'", "'\\''") + "'"
