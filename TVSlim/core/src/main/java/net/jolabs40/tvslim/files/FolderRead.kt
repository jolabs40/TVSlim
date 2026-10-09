package net.jolabs40.tvslim.files

import net.jolabs40.tvslim.shell.ShellResult

enum class EntryKind { FOLDER, FILE, OTHER }

/** An entry of a TV folder, as described by `stat`. */
data class RemoteEntry(
    val name: String,
    /** For a symlink, the type of its target, so `/sdcard` browses like a folder. */
    val kind: EntryKind,
    /** In bytes; meaningless for a folder. */
    val size: Long,
    /** Last modified time in milliseconds. */
    val date: Long,
    val link: Boolean = false,
) {
    val folder: Boolean get() = kind == EntryKind.FOLDER
}

/** Result of listing a folder. */
sealed interface FolderRead {
    val path: String

    data class Read(override val path: String, val entries: List<RemoteEntry>) : FolderRead

    data class NotFound(override val path: String) : FolderRead

    /** The folder exists but Android does not let the ADB shell open it (usually `/data`). */
    data class Rejected(override val path: String) : FolderRead

    /** [reason]: the raw answer from the connection or the TV. */
    data class Failed(override val path: String, val reason: String) : FolderRead
}

enum class ShortcutKind { INTERNAL, DOWNLOADS, MOVIES, MUSIC, IMAGES, VOLUME, TEMPORARY, ROOT }

/** A one-click folder shortcut. Each app localizes its label; a volume shows its own name. */
data class Shortcut(val kind: ShortcutKind, val path: String) {
    val name: String get() = RemotePath.name(path)

    companion object {
        /**
         * `/sdcard` rather than `/storage/emulated/0`: it is the path tutorials give, and leads to the same
         * place. `/data/local/tmp` is the only folder outside shared storage where the shell can write.
         */
        fun withVolumes(volumes: List<String>): List<Shortcut> = buildList {
            add(Shortcut(ShortcutKind.INTERNAL, START_FOLDER))
            add(Shortcut(ShortcutKind.DOWNLOADS, "$START_FOLDER/Download"))
            add(Shortcut(ShortcutKind.MOVIES, "$START_FOLDER/Movies"))
            add(Shortcut(ShortcutKind.MUSIC, "$START_FOLDER/Music"))
            add(Shortcut(ShortcutKind.IMAGES, "$START_FOLDER/Pictures"))
            volumes.forEach { add(Shortcut(ShortcutKind.VOLUME, "/storage/$it")) }
            add(Shortcut(ShortcutKind.TEMPORARY, "/data/local/tmp"))
            add(Shortcut(ShortcutKind.ROOT, RemotePath.ROOT))
        }
    }
}

/** Shared storage as apps see it; the starting folder. */
const val START_FOLDER = "/sdcard"

/**
 * Builds the folder listing command and parses its output.
 *
 * `stat -c` rather than `ls -l`, whose columns do not show where the date ends and a name with spaces begins.
 * `find -printf` alone would do, but Android's toybox lacks it (tested with toybox 0.8.9 on the TCL and 0.8.3
 * on the Shield).
 *
 * One `E|mode|size|date|name` line per entry, name last so it may contain `|`, then one `D|name` line per link
 * that leads to a folder: `[ -d ]` follows the link without another `stat`.
 */
internal object FolderReader {

    /** The folder does not exist, or is not a folder. */
    private const val CODE_NOT_FOUND = 2

    /** It exists but cannot be entered or listed. */
    private const val CODE_DENIED = 3

    private const val TYPE_MASK = 0xF000
    private const val TYPE_FOLDER = 0x4000
    private const val TYPE_FILE = 0x8000
    private const val TYPE_LINK = 0xA000

    /**
     * The TV shell (mksh) does not expand `.*` to `.` or `..`. A pattern with no match stays literal and
     * `stat` complains on stderr, which is discarded.
     */
    fun command(path: String): String {
        val quoted = quote(path)
        return "[ -d $quoted ] || exit $CODE_NOT_FOUND; " +
            "cd $quoted 2>/dev/null && ls -a >/dev/null 2>&1 || exit $CODE_DENIED; " +
            "stat -c 'E|%f|%s|%Y|%n' .* * 2>/dev/null; " +
            "for f in .* *; do [ -L \"\$f\" ] && [ -d \"\$f\" ] && echo \"D|\$f\"; done; exit 0"
    }

    fun read(path: String, response: ShellResult): FolderRead = when (response.code) {
        0 -> FolderRead.Read(path, entries(response.output))
        CODE_NOT_FOUND -> FolderRead.NotFound(path)
        CODE_DENIED -> FolderRead.Rejected(path)
        else -> FolderRead.Failed(path, response.output.ifBlank { "Code de retour ${response.code}." })
    }

    /** Folders first, then files, each sorted case-insensitively. */
    fun entries(output: String): List<RemoteEntry> {
        val lines = output.lines()
        val folderLinks = lines.filter { it.startsWith("D|") }.map { it.substring(2) }.toSet()
        return lines
            .filter { it.startsWith("E|") }
            .mapNotNull { entry(it, folderLinks) }
            .distinctBy { it.name }
            .sortedWith(compareBy<RemoteEntry> { !it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun entry(line: String, folderLinks: Set<String>): RemoteEntry? {
        val fields = line.split('|', limit = 5)
        if (fields.size < 5) return null
        val name = fields[4]
        if (name.isEmpty() || name == "." || name == "..") return null
        val type = (fields[1].toIntOrNull(16) ?: return null) and TYPE_MASK
        val link = type == TYPE_LINK
        return RemoteEntry(
            name = name,
            kind = when {
                type == TYPE_FOLDER || (link && name in folderLinks) -> EntryKind.FOLDER
                type == TYPE_FILE || link -> EntryKind.FILE
                else -> EntryKind.OTHER
            },
            size = fields[2].toLongOrNull() ?: 0L,
            date = (fields[3].toLongOrNull() ?: 0L) * 1_000L,
            link = link,
        )
    }

    /** Removable volumes (USB drive, SD card) are mounted under `/storage`, next to internal storage. */
    const val VOLUMES_COMMAND = "ls /storage"

    fun volumes(output: String): List<String> =
        output.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "emulated" && it != "self" && RemotePath.isValidName(it) }
            .sorted()
}
