package net.jolabs40.tvslim.windows.files

import net.jolabs40.tvslim.files.LocalFile
import net.jolabs40.tvslim.files.LocalBatch
import java.io.File
import java.io.InputStream
import java.nio.file.Files

private class DiskFile(private val file: File, override val path: String) : LocalFile {
    override val size: Long = file.length()
    override val date: Long = file.lastModified()
    override fun open(): InputStream = file.inputStream()
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
fun batchFrom(elements: List<File>): LocalBatch {
    val files = mutableListOf<LocalFile>()
    val folders = mutableListOf<String>()
    elements.distinct().forEach { element ->
        when {
            element.isFile -> files += DiskFile(element, element.name)
            element.isDirectory -> walk(element, element.name, files, folders, depth = 0)
        }
    }
    return LocalBatch(files, folders)
}

private fun walk(
    folder: File,
    path: String,
    files: MutableList<LocalFile>,
    folders: MutableList<String>,
    depth: Int,
) {
    folders += path
    if (depth >= MAX_DEPTH) return
    // An unreadable folder is sent empty rather than failing the whole upload.
    val children = folder.listFiles()?.sortedBy { it.name.lowercase() } ?: return
    children
        .filterNot { it.isHidden || Files.isSymbolicLink(it.toPath()) }
        .forEach { child ->
            val subPath = "$path/${child.name}"
            when {
                child.isFile -> files += DiskFile(child, subPath)
                child.isDirectory -> walk(child, subPath, files, folders, depth + 1)
            }
        }
}

/** Safety net in case a file system loop slips past both filters. */
private const val MAX_DEPTH = 64
