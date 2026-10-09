package net.jolabs40.tvslim.windows.outils

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Technical log, in a file that can be attached to a bug report.
 *
 * Same rule as on Android: events are always logged, details (TV address, command sent, target packages) only
 * when [detaillees] is on, so the log does not hold an inventory of the TV.
 */
object Traces {

    private const val TAILLE_MAXIMALE = 512 * 1024L
    private val horloge = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile
    private var fichier: File? = null

    /** True in development (no jpackage launcher, i.e. run from Gradle) or with `-Dtvslim.traces=detaillees`. */
    val detaillees: Boolean =
        System.getProperty("jpackage.app-path") == null ||
            System.getProperty("tvslim.traces") == "detaillees"

    fun ecrireDans(cible: File) {
        fichier = cible
    }

    fun info(etiquette: String, message: String) = ecrire("INFO ", etiquette, message, null)

    fun avertir(etiquette: String, message: String, erreur: Throwable? = null) =
        ecrire("AVERT", etiquette, message, erreur)

    @Synchronized
    private fun ecrire(niveau: String, etiquette: String, message: String, erreur: Throwable?) {
        val ligne = buildString {
            append(LocalDateTime.now().format(horloge)).append(' ').append(niveau).append(' ')
            append(etiquette).append(" — ").append(message)
            if (erreur != null) {
                // Stack trace only in detailed mode: network exception messages contain the address.
                if (detaillees) {
                    append('\n').append(StringWriter().also { erreur.printStackTrace(PrintWriter(it)) })
                } else {
                    append(" (").append(erreur.javaClass.simpleName).append(')')
                }
            }
        }
        System.err.println(ligne)
        val cible = fichier ?: return
        runCatching {
            cible.parentFile?.mkdirs()
            if (cible.length() > TAILLE_MAXIMALE) {
                val precedent = File(cible.path + ".1")
                precedent.delete()
                cible.renameTo(precedent)
            }
            cible.appendText(ligne + System.lineSeparator(), Charsets.UTF_8)
        }
    }
}

/** Returns the detail suffix, or nothing unless [Traces.detaillees]. */
internal fun detail(texte: String): String = if (Traces.detaillees) " : $texte" else ""
