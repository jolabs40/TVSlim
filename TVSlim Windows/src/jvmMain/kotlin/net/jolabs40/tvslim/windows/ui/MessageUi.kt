package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.result_summary
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Ce que la bannière du bas annonce.
 *
 * Les phrases vivent dans les ressources, donc traduites : le pilote ne décrit que *quoi* dire, et
 * la mise en mots se fait à l'affichage. Seuls les textes que l'application n'a pas écrits — la
 * sortie d'une commande, un motif du moteur — passent tels quels.
 */
sealed interface MessageUi {

    /** Une phrase des ressources. Un argument peut être lui-même un [MessageUi], rédigé d'abord. */
    data class Texte(
        val ressource: StringResource,
        val arguments: List<Any> = emptyList(),
    ) : MessageUi

    /** Ce que le téléviseur ou le moteur a répondu : on ne traduit pas ce qu'on n'a pas écrit. */
    data class Brut(val texte: String) : MessageUi

    /** Plusieurs phrases, une par ligne ; les vides sont sautées. */
    data class Lignes(val lignes: List<MessageUi>) : MessageUi

    /** Bilan d'un lot : « 12 sur 14 réussis », puis les premiers échecs, un par ligne. */
    data class Bilan(
        val succes: Int,
        val total: Int,
        val echecs: List<Pair<String, String>>,
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
        echecs.forEach { (nom, motif) -> append("\n").append(nom).append(" : ").append(motif) }
    }
}
