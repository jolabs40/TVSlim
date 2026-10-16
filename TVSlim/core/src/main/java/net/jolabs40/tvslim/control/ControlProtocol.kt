package net.jolabs40.tvslim.control

import net.jolabs40.tvslim.applications.ApplicationsReader
import java.util.Base64

/**
 * Keys of the remote control, with their Android key codes. A [holdable] key is pressed and released, and repeats
 * while held; the others are sent as a single tap, so a media key held down cannot skip ten tracks.
 */
enum class RemoteKey(val code: Int, val holdable: Boolean) {
    UP(19, true),
    DOWN(20, true),
    LEFT(21, true),
    RIGHT(22, true),
    CENTER(23, true),
    BACK(4, true),
    HOME(3, true),
    VOLUME_UP(24, true),
    VOLUME_DOWN(25, true),
    MUTE(164, false),
    PLAY_PAUSE(85, false),
    PREVIOUS(88, false),
    NEXT(87, false),
    DELETE(67, true),
    ENTER(66, false),
}

/** The helper's answer to an order. */
enum class AckStatus(val wire: String) {
    OK("ok"),

    /** Arrived too long after it was sent, and dropped. */
    LATE("late"),
    REFUSED("refused"),

    /** Nothing to do: a release for a key not held, sleep on a screen already off. */
    IGNORED("ignored"),
}

/** What the helper found on the device. */
data class HelperReady(
    val sdk: Int,
    /** `global` (InputManagerGlobal, Android 14+) or `legacy` (InputManager). */
    val injectionApi: String,
    val repeatTimeoutMs: Long,
    val repeatDelayMs: Long,
    /** null when the power state could not be read. */
    val screenOn: Boolean?,
    /** Whether text the key map cannot type can be pasted. */
    val clipboard: Boolean,
)

sealed interface HelperLine {
    data class Version(val version: Int) : HelperLine

    data class Ready(val ready: HelperReady) : HelperLine

    data class Ack(val id: Long, val status: AckStatus) : HelperLine

    /** A key the helper released by itself (phone silent, held too long). */
    data class Released(val code: Int) : HelperLine

    data class Screen(val on: Boolean?) : HelperLine

    data class Error(val reason: String) : HelperLine

    data class Bye(val reason: String) : HelperLine
}

/**
 * Lines exchanged with the remote-control helper (`aide/.../RemoteControl.java`), one order per line, fields
 * separated by tabs. Each order carries an id, answered by the helper, and the phone's clock, which lets the
 * helper drop an order that arrives late.
 */
object ControlProtocol {

    /** Not the Apps tab's path: that read deletes its copy when it ends, possibly while the remote starts. */
    const val HELPER_PATH = "/data/local/tmp/tvslim-remote.apk"

    /**
     * adbd sends SIGHUP to the helper when the stream closes, which kills the VM before it can release a held key.
     * Ignored, the helper sees the end of its input instead and releases everything.
     */
    const val COMMAND =
        "trap '' HUP PIPE; CLASSPATH=$HELPER_PATH exec app_process / ${ApplicationsReader.HELPER_CLASS} remote"

    const val QUIT = "Q"

    fun ping(id: Long, sentAt: Long) = line("P", id, sentAt)

    fun press(id: Long, sentAt: Long, code: Int) = line("D", id, sentAt, code)

    fun release(id: Long, sentAt: Long, code: Int) = line("U", id, sentAt, code)

    fun cancel(id: Long, sentAt: Long, code: Int) = line("C", id, sentAt, code)

    fun tap(id: Long, sentAt: Long, code: Int) = line("T", id, sentAt, code)

    fun wake(id: Long, sentAt: Long) = line("W", id, sentAt)

    fun sleep(id: Long, sentAt: Long) = line("S", id, sentAt)

    /** Base64, so tabs and line breaks typed on the phone cannot split the order. */
    fun text(id: Long, sentAt: Long, text: String) =
        line("X", id, sentAt, Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))

    private fun line(order: String, id: Long, sentAt: Long, vararg fields: Any) =
        (listOf(order, id, sentAt) + fields).joinToString("\t")

    /** One line from the helper, or null for anything unexpected (a warning printed by the runtime). */
    fun parse(line: String): HelperLine? {
        val fields = line.trimEnd('\r').split('\t')
        return when (fields[0]) {
            "R" -> if (fields.size < 7) null else HelperLine.Ready(
                HelperReady(
                    sdk = fields[1].toIntOrNull() ?: return null,
                    injectionApi = fields[2],
                    repeatTimeoutMs = fields[3].toLongOrNull() ?: return null,
                    repeatDelayMs = fields[4].toLongOrNull() ?: return null,
                    screenOn = screen(fields[5]),
                    clipboard = fields[6] == "1",
                ),
            )

            "K" -> {
                val id = fields.getOrNull(1)?.toLongOrNull() ?: return null
                val status = AckStatus.entries.firstOrNull { it.wire == fields.getOrNull(2) } ?: return null
                HelperLine.Ack(id, status)
            }

            "L" -> fields.getOrNull(1)?.toIntOrNull()?.let { HelperLine.Released(it) }
            "V" -> HelperLine.Screen(screen(fields.getOrNull(1)))
            "E" -> HelperLine.Error(fields.drop(1).joinToString(" "))
            "B" -> HelperLine.Bye(fields.getOrNull(1).orEmpty())
            else -> {
                val version = Regex("""TVSLIM_AIDE (\d+)""").matchEntire(line.trim())?.groupValues?.get(1)?.toInt()
                version?.let { HelperLine.Version(it) }
            }
        }
    }

    private fun screen(field: String?): Boolean? = when (field) {
        "1" -> true
        "0" -> false
        else -> null
    }
}
