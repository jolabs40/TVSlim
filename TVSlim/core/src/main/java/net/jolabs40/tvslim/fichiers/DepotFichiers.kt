package net.jolabs40.tvslim.fichiers

import java.io.InputStream

/**
 * A file from the PC or the phone, to send to the TV. Each app provides the source: the disk on Windows, a
 * document from the Android picker on the phone.
 */
interface FichierLocal {
    /** Path within the batch, `/`-separated, e.g. `Photos/2024/beach.jpg` for a file in a sent folder. */
    val chemin: String

    /** In bytes; 0 when unknown, which happens with some Android document providers. */
    val taille: Long

    /** Last modified time in milliseconds; 0 when unknown. */
    val date: Long

    /** Opens the content; the caller closes it. */
    fun ouvrir(): InputStream
}

/** Files and folders picked for upload. Folders are listed so that empty ones get created too. */
data class LotLocal(
    val fichiers: List<FichierLocal>,
    val dossiers: List<String> = emptyList(),
) {
    val taille: Long get() = fichiers.sumOf { it.taille }

    val vide: Boolean get() = fichiers.isEmpty() && dossiers.isEmpty()

    /**
     * Top-level names that land in the destination folder, each mapped to whether it is a folder. A sent
     * folder adds only its own name there; its files go inside.
     */
    val racines: Map<String, Boolean>
        get() = buildMap {
            dossiers.forEach { put(it.substringBefore('/'), true) }
            fichiers.forEach { fichier ->
                val premier = fichier.chemin.substringBefore('/')
                if (premier != fichier.chemin) put(premier, true) else if (premier !in this) put(premier, false)
            }
        }

    /** All folders to create, including those holding files, shallowest first. */
    val dossiersACreer: List<String>
        get() {
            val tous = sortedSetOf<String>()
            (dossiers + fichiers.mapNotNull { it.chemin.substringBeforeLast('/', "").ifEmpty { null } })
                .forEach { dossier ->
                    var courant = ""
                    dossier.split('/').forEach { etape ->
                        courant = if (courant.isEmpty()) etape else "$courant/$etape"
                        tous += courant
                    }
                }
            return tous.sortedBy { chemin -> chemin.count { it == '/' } }
        }
}

/** Why an upload is rejected before anything is sent. */
enum class RefusDepot {
    VIDE,

    /** A name the TV would not accept, or that would break listing its folder. */
    NOM_INVALIDE,

    /** A file where the TV has a folder of the same name, or the reverse. */
    NATURE_DIFFERENTE,

    /** The destination folder cannot be read, so there is no telling what would be overwritten. */
    DESTINATION_ILLISIBLE,
}

/** A checked upload, ready for confirmation. */
data class PlanDepot(
    val destination: String,
    val lot: LotLocal,
    /** Batch items the TV already has: a file will be replaced, a folder merged. */
    val existants: List<String>,
)

sealed interface ExamenDepot {
    data class Pret(val plan: PlanDepot) : ExamenDepot

    /** [noms]: the offending items, if any. */
    data class Refuse(val refus: RefusDepot, val noms: List<String> = emptyList()) : ExamenDepot
}

/** Upload to the TV, or copy from it to the PC; both report progress the same way. */
enum class SensTransfert { ENVOI, RECEPTION }

/** Transfer progress: current file and its index, bytes sent (or received) out of the total. */
data class AvanceeDepot(
    val fichier: String,
    val rang: Int,
    val nombre: Int,
    val envoye: Long,
    val total: Long,
    val sens: SensTransfert = SensTransfert.ENVOI,
)

data class EchecDepot(val chemin: String, val motif: String)

data class ResultatDepot(
    val destination: String,
    val envoyes: Int,
    val nombre: Int,
    val echecs: List<EchecDepot> = emptyList(),
    /** Stopped by the user. */
    val annule: Boolean = false,
    /** Stopped by a lost connection; the remaining files were not sent. */
    val interrompu: Boolean = false,
    /** For a copy to the PC, [destination] is a local folder and [envoyes] counts the copied files. */
    val sens: SensTransfert = SensTransfert.ENVOI,
) {
    val complet: Boolean get() = envoyes == nombre && echecs.isEmpty() && !annule && !interrompu
}
