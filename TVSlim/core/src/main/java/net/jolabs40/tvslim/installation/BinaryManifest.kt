package net.jolabs40.tvslim.installation

import java.nio.charset.Charset

/** What an APK declares about itself in its manifest. */
data class ApkManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** `null` when not declared, or declared as a preview codename. */
    val minSdk: Int?,
)

/** A binary XML element: its name and its attributes by name. */
internal data class BinaryElement(val name: String, val attributes: Map<String, BinaryValue>)

/** Attribute value: a string, an integer, or a resource reference that is not resolved. */
internal data class BinaryValue(val text: String?, val type: Int, val rawData: Int) {
    val integer: Int? get() = if (type in TYPE_INT_DEC..TYPE_INT_HEX) rawData else null
}

/**
 * Reads an APK's compiled manifest (`aapt`'s AXML, little-endian binary XML) to show package, version
 * and minimum Android before anything is sent to the TV. Uses no SDK tool or Android API, so the
 * Windows build compiles it as is.
 *
 * Three chunk types matter: the string pool (UTF-16 in a manifest, as `aapt2` enforces for old Android
 * versions; UTF-8 elsewhere), the resource ID map (names an `android:` attribute even when an
 * obfuscator blanked it), and start elements with their 20-byte attributes.
 */
internal object BinaryManifest {

    /** Parses [bytes], or returns `null` if they are not a manifest (truncated or crafted). */
    fun read(bytes: ByteArray): ApkManifest? {
        val elements = runCatching { elements(bytes) }.getOrNull() ?: return null
        val manifest = elements.firstOrNull { it.name == "manifest" } ?: return null
        val packageName = manifest.attributes["package"]?.text?.takeIf { it.isNotBlank() } ?: return null

        // Two unsigned 32-bit words; versionCodeMajor only exists since Android 9.
        val minor = manifest.attributes["versionCode"]?.integer?.toLong()?.and(MASK_32_BITS) ?: 0L
        val major = manifest.attributes["versionCodeMajor"]?.integer?.toLong()?.and(MASK_32_BITS) ?: 0L
        return ApkManifest(
            packageName = packageName,
            versionCode = (major shl 32) or minor,
            versionName = manifest.attributes["versionName"]?.text.orEmpty(),
            minSdk = elements.firstOrNull { it.name == "uses-sdk" }?.attributes?.get("minSdkVersion")?.integer,
        )
    }

    /** Returns all start elements of the document, in order. Throws on a malformed file. */
    fun elements(bytes: ByteArray): List<BinaryElement> {
        val reader = LittleEndianBytes(bytes)
        require(reader.u16(0) == TYPE_XML) { "pas un XML binaire" }

        var strings = emptyList<String>()
        var ids = IntArray(0)
        val elements = mutableListOf<BinaryElement>()
        var position = reader.u16(2)
        while (position + HEADER_SIZE <= bytes.size) {
            val type = reader.u16(position)
            val header = reader.u16(position + 2)
            val size = reader.i32(position + 4)
            // A zero size would loop forever; an oversized one would read out of bounds.
            require(size >= HEADER_SIZE && position.toLong() + size <= bytes.size) { "morceau malformé" }
            when (type) {
                TYPE_STRING_POOL -> strings = readStrings(reader, position, header, size)
                TYPE_RESOURCE_IDS -> ids = IntArray((size - header) / 4) { reader.i32(position + header + it * 4) }
                TYPE_START_ELEMENT -> elements += readElement(reader, position + header, strings, ids)
            }
            position += size
        }
        return elements
    }

    private fun readStrings(reader: LittleEndianBytes, position: Int, header: Int, size: Int): List<String> {
        val count = reader.i32(position + 8)
        require(count >= 0 && header + count.toLong() * 4 <= size) { "table des chaînes malformée" }
        val utf8 = reader.i32(position + 16) and UTF8_FLAG != 0
        val start = position + reader.i32(position + 20)
        return List(count) { index ->
            val offset = start + reader.i32(position + header + index * 4)
            if (utf8) utf8String(reader, offset) else utf16String(reader, offset)
        }
    }

    /** Two lengths precede the text: in UTF-16 characters (unused here), then in bytes. */
    private fun utf8String(reader: LittleEndianBytes, start: Int): String {
        var position = start + if (reader.u8(start) and 0x80 != 0) 2 else 1
        var length = reader.u8(position++)
        if (length and 0x80 != 0) length = ((length and 0x7F) shl 8) or reader.u8(position++)
        return reader.text(position, length, Charsets.UTF_8)
    }

    private fun utf16String(reader: LittleEndianBytes, start: Int): String {
        var position = start + 2
        var length = reader.u16(start)
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or reader.u16(position)
            position += 2
        }
        return reader.text(position, length * 2, Charsets.UTF_16LE)
    }

    private fun readElement(
        reader: LittleEndianBytes,
        extension: Int,
        strings: List<String>,
        ids: IntArray,
    ): BinaryElement {
        val start = reader.u16(extension + 8)
        val width = reader.u16(extension + 10)
        val count = reader.u16(extension + 12)
        val attributes = (0 until count).associate { index ->
            val attribute = extension + start + index * width
            val nameIndex = reader.i32(attribute + 4)
            val raw = reader.i32(attribute + 8)
            val type = reader.u8(attribute + 15)
            val rawData = reader.i32(attribute + 16)
            // Resource ID first: it survives an attribute name blanked by an obfuscator.
            val name = ids.getOrNull(nameIndex)?.let(NAMES_BY_ID::get)
                ?: strings.getOrElse(nameIndex) { "" }
            val text = when {
                raw >= 0 -> strings.getOrNull(raw)
                type == TYPE_STRING -> strings.getOrNull(rawData)
                else -> null
            }
            name to BinaryValue(text, type, rawData)
        }
        return BinaryElement(strings.getOrElse(reader.i32(extension + 4)) { "" }, attributes)
    }

    /** Little-endian reads. An out-of-range offset throws, and [read] catches it. */
    private class LittleEndianBytes(private val bytes: ByteArray) {
        fun u8(position: Int): Int = bytes[position].toInt() and 0xFF
        fun u16(position: Int): Int = u8(position) or (u8(position + 1) shl 8)
        fun i32(position: Int): Int = u16(position) or (u16(position + 2) shl 16)
        fun text(position: Int, length: Int, charset: Charset): String = String(bytes, position, length, charset)
    }

    private const val HEADER_SIZE = 8
    private const val TYPE_XML = 0x0003
    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_RESOURCE_IDS = 0x0180
    private const val TYPE_START_ELEMENT = 0x0102
    private const val UTF8_FLAG = 1 shl 8
    private const val TYPE_STRING = 0x03
    private const val MASK_32_BITS = 0xFFFFFFFFL

    /** The `android:` attributes read here, by framework resource ID. */
    private val NAMES_BY_ID = mapOf(
        0x0101021b to "versionCode",
        0x0101021c to "versionName",
        0x0101020c to "minSdkVersion",
        0x01010576 to "versionCodeMajor",
    )
}

private const val TYPE_INT_DEC = 0x10
private const val TYPE_INT_HEX = 0x11
