package net.jolabs40.tvslim.windows.tools

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Technical log, in a file that can be attached to a bug report.
 *
 * Same rule as on Android: events are always logged, details (TV address, command sent, target packages) only
 * when [verbose] is on, so the log does not hold an inventory of the TV.
 */
object AppLog {

    private const val MAXIMUM_SIZE = 512 * 1024L
    private val clock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile
    private var file: File? = null

    /** True in development (no jpackage launcher, i.e. run from Gradle) or with `-Dtvslim.traces=verbose`. */
    val verbose: Boolean =
        System.getProperty("jpackage.app-path") == null ||
            System.getProperty("tvslim.log") == "verbose"

    fun writeTo(target: File) {
        file = target
    }

    fun info(tag: String, message: String) = write("INFO ", tag, message, null)

    fun warn(tag: String, message: String, error: Throwable? = null) =
        write("AVERT", tag, message, error)

    @Synchronized
    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val line = buildString {
            append(LocalDateTime.now().format(clock)).append(' ').append(level).append(' ')
            append(tag).append(" — ").append(message)
            if (error != null) {
                // Stack trace only in detailed mode: network exception messages contain the address.
                if (verbose) {
                    append('\n').append(StringWriter().also { error.printStackTrace(PrintWriter(it)) })
                } else {
                    append(" (").append(error.javaClass.simpleName).append(')')
                }
            }
        }
        System.err.println(line)
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() > MAXIMUM_SIZE) {
                val previous = File(target.path + ".1")
                previous.delete()
                target.renameTo(previous)
            }
            target.appendText(line + System.lineSeparator(), Charsets.UTF_8)
        }
    }
}

/** Returns the detail suffix, or nothing unless [AppLog.verbose]. */
internal fun detail(text: String): String = if (AppLog.verbose) " : $text" else ""
