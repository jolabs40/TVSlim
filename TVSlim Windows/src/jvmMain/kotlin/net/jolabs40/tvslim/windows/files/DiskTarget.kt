package net.jolabs40.tvslim.windows.files

import net.jolabs40.tvslim.files.LocalTarget
import net.jolabs40.tvslim.files.LocalWrite
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Local folder receiving files copied from the TV.
 *
 * Android allows names Windows rejects (`:`, `?`, `"`, a trailing dot, `CON`), and timestamped screenshots often
 * contain `:`. Each path segment goes through [windowsName] instead of letting the write fail.
 *
 * A file is written next to its target with [TEMPORARY_SUFFIX] and renamed only once complete, so a stopped
 * copy leaves neither a truncated file nor an overwritten previous one.
 */
class DiskTarget(private val root: File) : LocalTarget {

    override fun describe(path: String): String = file(path).path

    override fun exists(path: String): Boolean = file(path).exists()

    override fun createFolder(path: String) {
        val folder = file(path)
        if (folder.isDirectory) return
        if (folder.exists()) throw IOException("Un fichier porte déjà ce nom : ${folder.path}")
        try {
            Files.createDirectories(folder.toPath())
        } catch (error: FileSystemException) {
            throw IOException(error.reason ?: "Création impossible : ${folder.path}", error)
        }
    }

    override fun write(path: String): LocalWrite {
        val target = file(path)
        // Files.move would silently replace an empty folder with the file.
        if (target.isDirectory) throw IOException("Un dossier porte déjà ce nom : ${target.path}")
        val temporary = File(target.parentFile, target.name + TEMPORARY_SUFFIX)
        // FileOutputStream's error message is in the Windows display language.
        return DiskWrite(target, temporary, FileOutputStream(temporary).buffered(BUFFER_SIZE))
    }

    private fun file(path: String): File =
        path.split('/').filter { it.isNotEmpty() }.fold(root) { folder, name -> File(folder, windowsName(name)) }

    private class DiskWrite(
        private val target: File,
        private val temporary: File,
        override val stream: OutputStream,
    ) : LocalWrite {

        private var valid = false

        override fun commit(date: Long) {
            stream.close()
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (error: FileSystemException) {
                // Usually the target file is open in another application.
                throw IOException(error.reason ?: "Remplacement impossible : ${target.path}", error)
            }
            valid = true
            if (date > 0) target.setLastModified(date)
        }

        override fun close() {
            runCatching { stream.close() }
            if (!valid) temporary.delete()
        }
    }

    companion object {
        const val TEMPORARY_SUFFIX = ".tvslim-partial"

        private const val BUFFER_SIZE = 256 * 1024

        private const val FORBIDDEN_CHARS = "<>:\"/\\|?*"

        /** Windows device names, reserved with or without an extension (`NUL.txt` too). */
        private val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).flatMap { listOf("COM$it", "LPT$it") } + listOf("COM¹", "COM²", "COM³", "LPT¹", "LPT²", "LPT³")

        /**
         * Returns a name Windows accepts: forbidden and control characters become `_`, trailing dots and spaces
         * (which Windows would strip silently) are removed, and device names get a `_` prefix.
         */
        fun windowsName(name: String): String {
            val cleanName = name
                .map { if (it in FORBIDDEN_CHARS || it.code < 0x20) '_' else it }
                .joinToString("")
                .trimEnd('.', ' ')
                .ifEmpty { "_" }
            return if (cleanName.substringBefore('.').trimEnd().uppercase() in RESERVED_NAMES) "_$cleanName" else cleanName
        }
    }
}
