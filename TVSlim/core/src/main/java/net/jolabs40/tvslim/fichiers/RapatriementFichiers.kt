package net.jolabs40.tvslim.fichiers

import java.io.Closeable
import java.io.OutputStream

/**
 * Destination for files copied from the TV: a local folder on Windows. Paths are relative to it and
 * `/`-separated; the target adapts them to its file system (an Android name may contain a `:` that Windows
 * rejects).
 */
interface CibleLocale {
    /** Display form of a target path, e.g. `C:\Users\...\Downloads\Movies`. Empty means the target itself. */
    fun decrire(chemin: String = ""): String

    fun existe(chemin: String): Boolean

    /** Creates [chemin] and its parents; an existing folder is not an error. */
    fun creerDossier(chemin: String)

    /** Opens [chemin] for writing. Nothing replaces the existing file before [EcritureLocale.valider]. */
    fun ecrire(chemin: String): EcritureLocale
}

/**
 * A file being written, temporary until validated. A stopped or cut-off copy leaves neither a truncated file
 * nor an overwritten previous one: closing without validating deletes what arrived.
 */
interface EcritureLocale : Closeable {
    val flux: OutputStream

    /** Moves the complete file into place, replacing any file of that name, dated [date] (ms, 0 if unknown). */
    fun valider(date: Long)
}

/** A file to copy: its path on the TV and its path in the target. */
data class FichierDistant(val distant: String, val local: String, val taille: Long, val date: Long)

/** A copy to the PC, ready to start. */
data class PlanRapatriement(
    /** What is copied from the TV: a file, or a folder with everything in it. */
    val source: String,
    val cible: CibleLocale,
    /** Name in the target: the file's name, or the folder that receives the rest. */
    val nom: String,
    val dossier: Boolean,
    val fichiers: List<FichierDistant>,
    /** Folders to create in the target, empty ones included, parents first. */
    val dossiers: List<String> = emptyList(),
    /** [nom] already exists in the target: a folder is merged and same-name files are replaced. */
    val existant: Boolean = false,
) {
    val taille: Long get() = fichiers.sumOf { it.taille }

    /** Display form of where things arrive: the copied folder, or the folder receiving the file. */
    val destination: String get() = cible.decrire(if (dossier) nom else "")
}

/** Why the contents of a TV folder could not be read. */
enum class RefusLecture { INTROUVABLE, REFUSE, ECHEC }

sealed interface ExamenRapatriement {
    data class Pret(val plan: PlanRapatriement) : ExamenRapatriement

    /** [motif]: the raw answer from the connection or the TV, for [RefusLecture.ECHEC]. */
    data class Illisible(val refus: RefusLecture, val motif: String = "") : ExamenRapatriement
}
