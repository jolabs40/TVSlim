package net.jolabs40.tvslim.windows.outils

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Traces techniques, dans un fichier qu'on peut joindre à un signalement de problème.
 *
 * Même règle que sur Android : l'**événement** est toujours consigné, le **détail** — adresse du
 * téléviseur, commande envoyée, paquets visés — seulement quand on le demande. Une trace n'a pas
 * à tenir l'inventaire d'un téléviseur.
 */
object Traces {

    private const val TAILLE_MAXIMALE = 512 * 1024L
    private val horloge = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile
    private var fichier: File? = null

    /**
     * Vrai en développement — lancée par Gradle, l'application ne passe pas par le lanceur
     * jpackage — ou quand on le demande explicitement avec `-Dtvslim.traces=detaillees`.
     */
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
                // La pile seulement en détail : le message d'une exception réseau porte l'adresse.
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

/** Le détail d'une trace, ou rien : voir [Traces.detaillees]. */
internal fun detail(texte: String): String = if (Traces.detaillees) " : $texte" else ""
