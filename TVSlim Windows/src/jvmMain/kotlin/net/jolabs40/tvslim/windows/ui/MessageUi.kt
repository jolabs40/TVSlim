package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.result_summary
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * A snackbar message, resolved to text only when displayed so it follows the UI language.
 *
 * Text the app did not write (command output, raw TV replies) is passed through untranslated.
 */
sealed interface MessageUi {

    /** A string resource. An argument may itself be a [MessageUi], resolved first. */
    data class Texte(
        val ressource: StringResource,
        val arguments: List<Any> = emptyList(),
    ) : MessageUi

    /** Raw reply from the TV or the engine, shown as is. */
    data class Brut(val texte: String) : MessageUi

    /** One message per line; blank ones are skipped. */
    data class Lignes(val lignes: List<MessageUi>) : MessageUi

    /** Batch result: "12 of 14 succeeded", then the first failures, one per line. */
    data class Bilan(
        val succes: Int,
        val total: Int,
        val echecs: List<Pair<String, MessageUi>>,
    ) : MessageUi
}

fun texte(ressource: StringResource, vararg arguments: Any): MessageUi =
    MessageUi.Texte(ressource, arguments.toList())

suspend fun MessageUi.rediger(): String = when (this) {
    is MessageUi.Texte -> getString(
        ressource,
        *arguments.map { if (it is MessageUi) it.rediger() else it }.toTypedArray(),
    )

    is MessageUi.Brut -> texte
    is MessageUi.Lignes -> lignes.map { it.rediger() }.filter { it.isNotBlank() }.joinToString("\n")
    is MessageUi.Bilan -> buildString {
        append(getString(Res.string.result_summary, succes, total))
        echecs.forEach { (nom, motif) -> append("\n").append(nom).append(" : ").append(motif.rediger()) }
    }
}
