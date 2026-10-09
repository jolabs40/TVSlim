package net.jolabs40.tvslim.screen

import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.shell.BinaryReader
import net.jolabs40.tvslim.shell.BinaryOutput
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Why a screenshot failed; each app localizes the message. */
enum class CaptureCause {
    /** No session, or the connection dropped while reading. */
    CONNECTION,

    /** `screencap` returned an error code. */
    REJECTED,

    /** Output is not a PNG: an Android without `-p`, or truncated output. */
    UNREADABLE,
}

sealed interface CaptureResult {
    class Succeeded(val png: ByteArray, val width: Int, val height: Int) : CaptureResult

    data class Failed(val cause: CaptureCause, val detail: String) : CaptureResult
}

/**
 * Captures the TV screen like `adb exec-out screencap -p`: the PNG comes through stdout and nothing is written
 * on the TV.
 *
 * DRM-protected content (Netflix, most channels) comes out black. The TV decides this, and it cannot be told
 * apart from a genuinely black screen.
 */
class ScreenCapture(private val reader: BinaryReader) {

    suspend fun takeCapture(): CaptureResult {
        val output = reader.readBinary(COMMAND)
        val code = output.code ?: return CaptureResult.Failed(CaptureCause.CONNECTION, output.reason)
        if (code != 0) {
            return CaptureResult.Failed(CaptureCause.REJECTED, output.errors.ifBlank { "code $code" }.trim())
        }
        val (width, height) = dimensions(output.bytes)
            ?: return CaptureResult.Failed(CaptureCause.UNREADABLE, excerpt(output))
        return CaptureResult.Succeeded(output.bytes, width, height)
    }

    private fun excerpt(output: BinaryOutput): String = output.errors.ifBlank {
        String(output.bytes.copyOf(minOf(output.bytes.size, 120)), Charsets.UTF_8)
    }.trim()

    companion object {
        const val COMMAND = "screencap -p"

        private val SIGNATURE_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        /**
         * Reads width and height from the PNG `IHDR` header; `null` if not a PNG. A PNG mangled by a shell
         * (`\n` turned into `\r\n`) fails here on its signature.
         */
        fun dimensions(bytes: ByteArray): Pair<Int, Int>? {
            if (bytes.size < 24) return null
            if (!bytes.copyOf(8).contentEquals(SIGNATURE_PNG)) return null
            if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
            val width = integer(bytes, 16)
            val height = integer(bytes, 20)
            return if (width > 0 && height > 0) width to height else null
        }

        /**
         * File name for a screenshot or video: device and timestamp, with nothing Windows or Android rejects
         * in a file name, e.g. `TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-15-30.png`.
         */
        fun fileName(info: DeviceInfo, instant: LocalDateTime, extension: String): String =
            "TVSlim-${info.fileSafeName.take(60)}-${instant.format(TIMESTAMP_FORMAT)}.$extension"

        private fun integer(bytes: ByteArray, start: Int): Int =
            (bytes[start].toInt() and 0xFF shl 24) or
                (bytes[start + 1].toInt() and 0xFF shl 16) or
                (bytes[start + 2].toInt() and 0xFF shl 8) or
                (bytes[start + 3].toInt() and 0xFF)
    }
}
