package net.jolabs40.tvslim.installation

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Reads the certificate that signs an APK from its signing block (scheme v3, else v2).
 *
 * The signature itself is not verified here: Android does that at install time and rejects an APK whose
 * signature does not match its certificate. Comparing this certificate's fingerprint with the expected
 * one is therefore enough to send only our own APKs to the TV: a file swapped in transit would carry
 * another certificate, or a signature Android rejects.
 *
 * Format: https://source.android.com/docs/security/features/apksigning/v2#apk-signing-block
 */
object ApkSignature {

    /** SHA-256 of the first signer's certificate, lowercase hex; null without a readable signing block. */
    fun certificateFingerprint(file: File): String? =
        runCatching { RandomAccessFile(file, "r").use(::read) }.getOrNull()

    private fun read(f: RandomAccessFile): String? {
        val size = f.length()
        if (size < END_OF_CENTRAL_DIRECTORY_SIZE) return null

        // Search backwards for the end of central directory record: a comment of up to 64 KB may follow it.
        val queue = minOf(size, END_OF_CENTRAL_DIRECTORY_SIZE + 0xFFFFL).toInt()
        val end = readBytes(f, size - queue, queue)
        val b = ByteBuffer.wrap(end).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (queue - END_OF_CENTRAL_DIRECTORY_SIZE.toInt() downTo 0).firstOrNull { b.getInt(it) == END_OF_CENTRAL_DIRECTORY_SIGNATURE }
            ?: return null
        val centralDirectoryStart = b.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

        // The signing block ends right before the central directory with its size, then "APK Sig Block 42".
        if (centralDirectoryStart < 32) return null
        val footer = readBytes(f, centralDirectoryStart - 24, 24)
        if (String(footer, 8, 16, Charsets.US_ASCII) != MAGIC) return null
        val blockSize = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        if (blockSize < 24 || blockSize > MAX_BLOCK_SIZE || blockSize + 8 > centralDirectoryStart) return null
        val head = readBytes(f, centralDirectoryStart - blockSize - 8, 8)
        if (ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getLong(0) != blockSize) return null

        // Between the two size fields: pairs of 8-byte length, 4-byte ID, value.
        val pairs = ByteBuffer.wrap(readBytes(f, centralDirectoryStart - blockSize, (blockSize - 24).toInt()))
            .order(ByteOrder.LITTLE_ENDIAN)
        val values = HashMap<Int, ByteBuffer>()
        while (pairs.remaining() >= 12) {
            val length = pairs.getLong()
            if (length < 4 || length - 4 > pairs.remaining() - 4) return null
            val id = pairs.getInt()
            values[id] = slice(pairs, (length - 4).toInt())
        }
        val scheme = values[ID_V3] ?: values[ID_V2] ?: return null
        return firstCertificate(scheme)?.let(::sha256)
    }

    /**
     * v2 and v3 start the same way: the signer sequence; in the first signer, its signed data; in that,
     * the digests then the certificates, the first of which is the signer's.
     */
    private fun firstCertificate(scheme: ByteBuffer): ByteArray? {
        val signers = prefixed(scheme) ?: return null
        val signer = prefixed(signers) ?: return null
        val data = prefixed(signer) ?: return null
        prefixed(data) ?: return null // digests
        val certificates = prefixed(data) ?: return null
        val certificate = prefixed(certificates) ?: return null
        return ByteArray(certificate.remaining()).also { certificate.get(it) }
    }

    /** Reads a sequence prefixed by its 4-byte length; null if it overflows what is left. */
    private fun prefixed(source: ByteBuffer): ByteBuffer? {
        if (source.remaining() < 4) return null
        val length = source.getInt()
        if (length < 0 || length > source.remaining()) return null
        return slice(source, length)
    }

    private fun slice(source: ByteBuffer, length: Int): ByteBuffer {
        val view = source.slice().order(ByteOrder.LITTLE_ENDIAN)
        view.limit(length)
        source.position(source.position() + length)
        return view
    }

    private fun readBytes(f: RandomAccessFile, position: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        f.seek(position)
        f.readFully(bytes)
        return bytes
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private const val END_OF_CENTRAL_DIRECTORY_SIZE = 22L
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
    private const val MAGIC = "APK Sig Block 42"
    private const val ID_V2 = 0x7109871a
    private const val ID_V3 = 0xf05368c0.toInt()

    /** A signing block weighs a few KB; anything above this cap is crafted. */
    private const val MAX_BLOCK_SIZE = 16L * 1024 * 1024
}
