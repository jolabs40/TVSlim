package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.result_summary
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * A snackbar message, resolved to text only when displayed so it follows the UI language.
 *
 * Text the app did not write (command output, raw TV replies) is passed through untranslated.
 */
sealed interface UiMessage {

    /** A string resource. An argument may itself be a [UiMessage], resolved first. */
    data class Localized(
        val resource: StringResource,
        val arguments: List<Any> = emptyList(),
    ) : UiMessage

    /** Raw reply from the TV or the engine, shown as is. */
    data class Raw(val text: String) : UiMessage

    /** One message per line; blank ones are skipped. */
    data class Lines(val lines: List<UiMessage>) : UiMessage

    /** Batch result: "12 of 14 succeeded", then the first failures, one per line. */
    data class Summary(
        val successes: Int,
        val total: Int,
        val failures: List<Pair<String, UiMessage>>,
    ) : UiMessage
}

fun text(resource: StringResource, vararg arguments: Any): UiMessage =
    UiMessage.Localized(resource, arguments.toList())

suspend fun UiMessage.phrase(): String = when (this) {
    is UiMessage.Localized -> getString(
        resource,
        *arguments.map { if (it is UiMessage) it.phrase() else it }.toTypedArray(),
    )

    is UiMessage.Raw -> text
    is UiMessage.Lines -> lines.map { it.phrase() }.filter { it.isNotBlank() }.joinToString("\n")
    is UiMessage.Summary -> buildString {
        append(getString(Res.string.result_summary, successes, total))
        failures.forEach { (name, reason) -> append("\n").append(name).append(" : ").append(reason.phrase()) }
    }
}
