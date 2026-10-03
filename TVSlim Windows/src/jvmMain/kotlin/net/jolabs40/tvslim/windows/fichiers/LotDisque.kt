package net.jolabs40.tvslim.windows.fichiers

import net.jolabs40.tvslim.fichiers.FichierLocal
import net.jolabs40.tvslim.fichiers.LotLocal
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Un fichier du disque, et son chemin dans le lot. */
private class FichierDisque(private val fichier: File, override val chemin: String) : FichierLocal {
    override val taille: Long = fichier.length()
    override val date: Long = fichier.lastModified()
    override fun ouvrir(): InputStream = fichier.inputStream()
}

/**
 * Ce qu'on a glissé ou choisi — fichiers et dossiers mêlés — mis à plat : chaque fichier avec son chemin
 * relatif à ce qui a été choisi, chaque dossier traversé, vides compris.
 *
 * Dans un dossier, on écarte :
 *  - les liens symboliques, qui pourraient mener à un parent et tourner sans fin ;
 *  - ce que Windows cache — `desktop.ini`, `Thumbs.db`, et les jonctions de compatibilité comme
 *    « Application Data », qui pointent sur leur propre dossier. Ce qu'on a choisi soi-même part, caché ou non.
 */
fun lotDepuis(elements: List<File>): LotLocal {
    val fichiers = mutableListOf<FichierLocal>()
    val dossiers = mutableListOf<String>()
    elements.distinct().forEach { element ->
        when {
            element.isFile -> fichiers += FichierDisque(element, element.name)
            element.isDirectory -> parcourir(element, element.name, fichiers, dossiers, profondeur = 0)
        }
    }
    return LotLocal(fichiers, dossiers)
}

private fun parcourir(
    dossier: File,
    chemin: String,
    fichiers: MutableList<FichierLocal>,
    dossiers: MutableList<String>,
    profondeur: Int,
) {
    dossiers += chemin
    if (profondeur >= PROFONDEUR_MAX) return
    // Un dossier illisible ne rend rien : il arrive vide, plutôt que de faire échouer tout l'envoi.
    val enfants = dossier.listFiles()?.sortedBy { it.name.lowercase() } ?: return
    enfants
        .filterNot { it.isHidden || Files.isSymbolicLink(it.toPath()) }
        .forEach { enfant ->
            val sousChemin = "$chemin/${enfant.name}"
            when {
                enfant.isFile -> fichiers += FichierDisque(enfant, sousChemin)
                enfant.isDirectory -> parcourir(enfant, sousChemin, fichiers, dossiers, profondeur + 1)
            }
        }
}

/** Une garde, au cas où un détour du système de fichiers échapperait aux deux filtres. */
private const val PROFONDEUR_MAX = 64
