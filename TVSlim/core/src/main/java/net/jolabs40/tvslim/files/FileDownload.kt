package net.jolabs40.tvslim.files

import java.io.Closeable
import java.io.OutputStream

/**
 * Destination for files copied from the TV: a local folder on Windows. Paths are relative to it and
 * `/`-separated; the target adapts them to its file system (an Android name may contain a `:` that Windows
 * rejects).
 */
interface LocalTarget {
    /** Display form of a target path, e.g. `C:\Users\...\Downloads\Movies`. Empty means the target itself. */
    fun describe(path: String = ""): String

    fun exists(path: String): Boolean

    /** Creates [path] and its parents; an existing folder is not an error. */
    fun createFolder(path: String)

    /** Opens [path] for writing. Nothing replaces the existing file before [LocalWrite.commit]. */
    fun write(path: String): LocalWrite
}

/**
 * A file being written, temporary until validated. A stopped or cut-off copy leaves neither a truncated file
 * nor an overwritten previous one: closing without validating deletes what arrived.
 */
interface LocalWrite : Closeable {
    val stream: OutputStream

    /** Moves the complete file into place, replacing any file of that name, dated [date] (ms, 0 if unknown). */
    fun commit(date: Long)
}

/** A file to copy: its path on the TV and its path in the target. */
data class RemoteFile(val remote: String, val local: String, val size: Long, val date: Long)

/** A copy to the PC, ready to start. */
data class DownloadPlan(
    /** What is copied from the TV: a file, or a folder with everything in it. */
    val source: String,
    val target: LocalTarget,
    /** Name in the target: the file's name, or the folder that receives the rest. */
    val name: String,
    val folder: Boolean,
    val files: List<RemoteFile>,
    /** Folders to create in the target, empty ones included, parents first. */
    val folders: List<String> = emptyList(),
    /** [name] already exists in the target: a folder is merged and same-name files are replaced. */
    val alreadyExists: Boolean = false,
) {
    val size: Long get() = files.sumOf { it.size }

    /** Display form of where things arrive: the copied folder, or the folder receiving the file. */
    val destination: String get() = target.describe(if (folder) name else "")
}

/** Why the contents of a TV folder could not be read. */
enum class ReadRejection { NOT_FOUND, DENIED, FAILED }

sealed interface DownloadReview {
    data class Ready(val plan: DownloadPlan) : DownloadReview

    /** [reason]: the raw answer from the connection or the TV, for [ReadRejection.FAILED]. */
    data class Unreadable(val rejection: ReadRejection, val reason: String = "") : DownloadReview
}
