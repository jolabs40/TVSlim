package net.jolabs40.tvslim.files

import net.jolabs40.tvslim.shell.ShellResult

/** A file found in a TV folder, with its path relative to that folder. */
data class InventoryFile(val path: String, val size: Long, val date: Long)

/** Recursive contents of a TV folder: subfolders (empty ones included) and files. */
data class Inventory(val folders: List<String>, val files: List<InventoryFile>) {
    val size: Long get() = files.sumOf { it.size }
}

internal sealed interface InventoryRead {
    data class Read(val inventory: Inventory) : InventoryRead

    data object Protected : InventoryRead

    data class Unreadable(val rejection: ReadRejection, val reason: String = "") : InventoryRead
}

/**
 * Lists a folder recursively before it is copied or deleted, and builds the delete command.
 *
 * Uses `find -exec stat {} +` because Android's toybox `find` has no `-printf`. One line per entry,
 * `D|0|date|./path` or `F|size|date|./path`, path last so it may contain `|`. Symlinks are not followed.
 * Tested with toybox 0.8.9 (TCL) and 0.8.3 (Shield).
 */
internal object FolderInventory {

    private const val CODE_NOT_FOUND = 2
    private const val CODE_DENIED = 3
    private const val CODE_PROTECTED = 5

    /**
     * Storage roots never deleted wholesale: internal storage under its three names, each `/storage` volume
     * (USB drive, SD card), the apps' `Android` folder, and `/data/local/tmp`.
     */
    private const val ROOTS = "/sdcard /sdcard/Android /storage/* /storage/emulated/* " +
        "/storage/emulated/0 /storage/self/primary /data/local/tmp"

    /**
     * Exits with [CODE_PROTECTED] if the quoted folder is one of [ROOTS] or contains one (`/storage/emulated`
     * contains internal storage). Paths are compared after resolving links: `/sdcard`, `/storage/self/primary`
     * and `/storage/emulated/0` are the same folder. An `rm -rf` reaching a root would delete everything the
     * ADB shell is allowed to delete.
     */
    private fun guard(quoted: String): String =
        "c=\$(readlink -f $quoted); [ -n \"\$c\" ] || exit $CODE_NOT_FOUND; " +
            "for r in $ROOTS; do r=\$(readlink -f \"\$r\") && " +
            "case \"\$r/\" in \"\${c%/}/\"*) exit $CODE_PROTECTED;; esac; done"

    /** [guard]: before a delete, rejects up front what [deletionCommand] would refuse. */
    fun command(path: String, guard: Boolean): String {
        val quoted = quote(path)
        return (if (guard) guard(quoted) + "; " else "") +
            "[ -d $quoted ] || exit $CODE_NOT_FOUND; " +
            "cd $quoted 2>/dev/null && ls -a >/dev/null 2>&1 || exit $CODE_DENIED; " +
            "find . -type d -exec stat -c 'D|0|%Y|%n' {} + 2>/dev/null; " +
            "find . -type f -exec stat -c 'F|%s|%Y|%n' {} + 2>/dev/null; exit 0"
    }

    /**
     * `rm -f` and `rm -rf` are safe to replay after a disconnect (what is gone stays gone), so they go through
     * the regular command path. A link gets no `-r`: `rm` would not follow it anyway.
     */
    fun deletionCommand(path: String, kind: DeletionKind): String {
        val quoted = quote(path)
        return when (kind) {
            DeletionKind.FOLDER -> guard(quoted) + "; rm -rf $quoted"
            DeletionKind.FILE, DeletionKind.LINK -> "rm -f $quoted"
        }
    }

    fun read(response: ShellResult): InventoryRead = when (response.code) {
        0 -> InventoryRead.Read(inventory(response.output))
        CODE_NOT_FOUND -> InventoryRead.Unreadable(ReadRejection.NOT_FOUND)
        CODE_DENIED -> InventoryRead.Unreadable(ReadRejection.DENIED)
        CODE_PROTECTED -> InventoryRead.Protected
        else -> InventoryRead.Unreadable(ReadRejection.FAILED, response.output.ifBlank { "Code de retour ${response.code}." })
    }

    fun deletion(name: String, response: ShellResult): EntryDeletion = when {
        response.succeeded -> EntryDeletion(DeletionOutcome.DELETED, name)
        response.code == CODE_PROTECTED -> EntryDeletion(DeletionOutcome.PROTECTED, name)
        else -> EntryDeletion(DeletionOutcome.FAILED, name, response.output.ifBlank { "Code de retour ${response.code}." })
    }

    /** Parent folders first; the folder itself (`.`) is not included. */
    fun inventory(output: String): Inventory {
        val folders = mutableListOf<String>()
        val files = mutableListOf<InventoryFile>()
        output.lines().forEach { line ->
            val fields = line.split('|', limit = 4)
            if (fields.size < 4 || !fields[3].startsWith("./")) return@forEach
            val path = fields[3].removePrefix("./")
            if (path.isEmpty()) return@forEach
            when (fields[0]) {
                "D" -> folders += path
                "F" -> files += InventoryFile(
                    path = path,
                    size = fields[1].toLongOrNull() ?: 0L,
                    date = (fields[2].toLongOrNull() ?: 0L) * 1_000L,
                )
            }
        }
        return Inventory(folders.sortedBy { path -> path.count { it == '/' } }, files)
    }
}
