package net.jolabs40.tvslim.files

/**
 * TV-side paths: Unix style, `/`-separated, always absolute here.
 *
 * They end up in shell commands, and file names come from the user (a folder dragged from Explorer, a
 * document picked on the phone), so they may contain a quote or a semicolon. [quote] passes them intact.
 */
object RemotePath {

    const val ROOT = "/"

    /** Maximum name length on Android file systems, in bytes rather than characters. */
    private const val MAX_NAME_BYTES = 255

    /** Normalizes a path: no `//`, `.`, `..` or trailing `/`. A relative path starts from the root. */
    fun normalize(path: String): String {
        val steps = mutableListOf<String>()
        path.split('/').forEach { step ->
            when (step) {
                "", "." -> Unit
                ".." -> if (steps.isNotEmpty()) steps.removeAt(steps.lastIndex)
                else -> steps += step
            }
        }
        return steps.joinToString(separator = "/", prefix = "/")
    }

    fun join(folder: String, name: String): String = if (folder == ROOT) "/$name" else "$folder/$name"

    /** Parent folder of [path]; null for the root. */
    fun parent(path: String): String? =
        if (path == ROOT) null else path.substringBeforeLast('/').ifEmpty { ROOT }

    fun name(path: String): String = path.substringAfterLast('/')

    /** Breadcrumb from the root to [path]: each step with its name and full path. */
    fun steps(path: String): List<PathStep> {
        val steps = mutableListOf(PathStep(ROOT, ROOT))
        var current = ROOT
        normalize(path).split('/').filter { it.isNotEmpty() }.forEach { name ->
            current = join(current, name)
            steps += PathStep(name, current)
        }
        return steps
    }

    /**
     * Whether a name may be created on the TV: not empty, not `.` or `..`, no `/` or control character, and
     * at most 255 bytes.
     *
     * Android accepts control characters, but a newline would break the line-based folder listing and make
     * the file unreadable from TV Slim.
     */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it.code < 0x20 || it.code == 0x7F } &&
            name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES
}

data class PathStep(val name: String, val path: String)

/** Single-quotes [text] for the shell. Only the quote itself needs handling: close, escape, reopen. */
fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
