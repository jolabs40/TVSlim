package net.jolabs40.tvslim.files

import java.io.InputStream

/**
 * A file from the PC or the phone, to send to the TV. Each app provides the source: the disk on Windows, a
 * document from the Android picker on the phone.
 */
interface LocalFile {
    /** Path within the batch, `/`-separated, e.g. `Photos/2024/beach.jpg` for a file in a sent folder. */
    val path: String

    /** In bytes; 0 when unknown, which happens with some Android document providers. */
    val size: Long

    /** Last modified time in milliseconds; 0 when unknown. */
    val date: Long

    /** Opens the content; the caller closes it. */
    fun open(): InputStream
}

/** Files and folders picked for upload. Folders are listed so that empty ones get created too. */
data class LocalBatch(
    val files: List<LocalFile>,
    val folders: List<String> = emptyList(),
) {
    val size: Long get() = files.sumOf { it.size }

    val empty: Boolean get() = files.isEmpty() && folders.isEmpty()

    /**
     * Top-level names that land in the destination folder, each mapped to whether it is a folder. A sent
     * folder adds only its own name there; its files go inside.
     */
    val roots: Map<String, Boolean>
        get() = buildMap {
            folders.forEach { put(it.substringBefore('/'), true) }
            files.forEach { file ->
                val first = file.path.substringBefore('/')
                if (first != file.path) put(first, true) else if (first !in this) put(first, false)
            }
        }

    /** All folders to create, including those holding files, shallowest first. */
    val foldersToCreate: List<String>
        get() {
            val all = sortedSetOf<String>()
            (folders + files.mapNotNull { it.path.substringBeforeLast('/', "").ifEmpty { null } })
                .forEach { folder ->
                    var current = ""
                    folder.split('/').forEach { step ->
                        current = if (current.isEmpty()) step else "$current/$step"
                        all += current
                    }
                }
            return all.sortedBy { path -> path.count { it == '/' } }
        }
}

/** Why an upload is rejected before anything is sent. */
enum class UploadRejection {
    EMPTY,

    /** A name the TV would not accept, or that would break listing its folder. */
    INVALID_NAME,

    /** A file where the TV has a folder of the same name, or the reverse. */
    KIND_MISMATCH,

    /** The destination folder cannot be read, so there is no telling what would be overwritten. */
    UNREADABLE_DESTINATION,
}

/** A checked upload, ready for confirmation. */
data class UploadPlan(
    val destination: String,
    val batch: LocalBatch,
    /** Batch items the TV already has: a file will be replaced, a folder merged. */
    val existing: List<String>,
)

sealed interface UploadReview {
    data class Ready(val plan: UploadPlan) : UploadReview

    /** [names]: the offending items, if any. */
    data class Rejected(val rejection: UploadRejection, val names: List<String> = emptyList()) : UploadReview
}

/** Upload to the TV, or copy from it to the PC; both report progress the same way. */
enum class TransferDirection { UPLOAD, DOWNLOAD }

/** Transfer progress: current file and its index, bytes sent (or received) out of the total. */
data class UploadProgress(
    val file: String,
    val index: Int,
    val count: Int,
    val sent: Long,
    val total: Long,
    val direction: TransferDirection = TransferDirection.UPLOAD,
)

data class UploadFailure(val path: String, val reason: String)

data class UploadResult(
    val destination: String,
    val sentCount: Int,
    val count: Int,
    val failures: List<UploadFailure> = emptyList(),
    /** Stopped by the user. */
    val cancelled: Boolean = false,
    /** Stopped by a lost connection; the remaining files were not sent. */
    val interrupted: Boolean = false,
    /** For a copy to the PC, [destination] is a local folder and [sentCount] counts the copied files. */
    val direction: TransferDirection = TransferDirection.UPLOAD,
) {
    val complete: Boolean get() = sentCount == count && failures.isEmpty() && !cancelled && !interrupted
}
