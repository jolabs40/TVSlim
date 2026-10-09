package net.jolabs40.tvslim.windows.fichiers

import net.jolabs40.tvslim.fichiers.FichierLocal
import net.jolabs40.tvslim.fichiers.LotLocal
import java.io.File
import java.io.InputStream
import java.nio.file.Files

private class FichierDisque(private val fichier: File, override val chemin: String) : FichierLocal {
    override val taille: Long = fichier.length()
    override val date: Long = fichier.lastModified()
    override fun ouvrir(): InputStream = fichier.inputStream()
}

/**
 * Flattens dropped or picked files and folders: every file with its path relative to the selection, and every
 * folder traversed, empty ones included.
 *
 * Inside folders, skips:
 *  - symlinks, which could point to a parent and loop forever;
 *  - hidden items: `desktop.ini`, `Thumbs.db`, and compatibility junctions like "Application Data" that point
 *    to their own parent. Items the user picked directly are kept even if hidden.
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
    // An unreadable folder is sent empty rather than failing the whole upload.
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

/** Safety net in case a file system loop slips past both filters. */
private const val PROFONDEUR_MAX = 64
