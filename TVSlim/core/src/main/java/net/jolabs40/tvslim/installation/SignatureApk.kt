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
object SignatureApk {

    /** SHA-256 of the first signer's certificate, lowercase hex; null without a readable signing block. */
    fun empreinteCertificat(fichier: File): String? =
        runCatching { RandomAccessFile(fichier, "r").use(::lire) }.getOrNull()

    private fun lire(f: RandomAccessFile): String? {
        val taille = f.length()
        if (taille < TAILLE_FIN_REPERTOIRE) return null

        // Search backwards for the end of central directory record: a comment of up to 64 KB may follow it.
        val queue = minOf(taille, TAILLE_FIN_REPERTOIRE + 0xFFFFL).toInt()
        val fin = lireOctets(f, taille - queue, queue)
        val b = ByteBuffer.wrap(fin).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (queue - TAILLE_FIN_REPERTOIRE.toInt() downTo 0).firstOrNull { b.getInt(it) == SIGNATURE_FIN_REPERTOIRE }
            ?: return null
        val debutRepertoire = b.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

        // The signing block ends right before the central directory with its size, then "APK Sig Block 42".
        if (debutRepertoire < 32) return null
        val pied = lireOctets(f, debutRepertoire - 24, 24)
        if (String(pied, 8, 16, Charsets.US_ASCII) != MAGIE) return null
        val tailleBloc = ByteBuffer.wrap(pied).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        if (tailleBloc < 24 || tailleBloc > TAILLE_MAX_BLOC || tailleBloc + 8 > debutRepertoire) return null
        val tete = lireOctets(f, debutRepertoire - tailleBloc - 8, 8)
        if (ByteBuffer.wrap(tete).order(ByteOrder.LITTLE_ENDIAN).getLong(0) != tailleBloc) return null

        // Between the two size fields: pairs of 8-byte length, 4-byte ID, value.
        val paires = ByteBuffer.wrap(lireOctets(f, debutRepertoire - tailleBloc, (tailleBloc - 24).toInt()))
            .order(ByteOrder.LITTLE_ENDIAN)
        val valeurs = HashMap<Int, ByteBuffer>()
        while (paires.remaining() >= 12) {
            val longueur = paires.getLong()
            if (longueur < 4 || longueur - 4 > paires.remaining() - 4) return null
            val id = paires.getInt()
            valeurs[id] = tranche(paires, (longueur - 4).toInt())
        }
        val schema = valeurs[ID_V3] ?: valeurs[ID_V2] ?: return null
        return premierCertificat(schema)?.let(::sha256)
    }

    /**
     * v2 and v3 start the same way: the signer sequence; in the first signer, its signed data; in that,
     * the digests then the certificates, the first of which is the signer's.
     */
    private fun premierCertificat(schema: ByteBuffer): ByteArray? {
        val signataires = prefixee(schema) ?: return null
        val signataire = prefixee(signataires) ?: return null
        val donnees = prefixee(signataire) ?: return null
        prefixee(donnees) ?: return null // digests
        val certificats = prefixee(donnees) ?: return null
        val certificat = prefixee(certificats) ?: return null
        return ByteArray(certificat.remaining()).also { certificat.get(it) }
    }

    /** Reads a sequence prefixed by its 4-byte length; null if it overflows what is left. */
    private fun prefixee(source: ByteBuffer): ByteBuffer? {
        if (source.remaining() < 4) return null
        val longueur = source.getInt()
        if (longueur < 0 || longueur > source.remaining()) return null
        return tranche(source, longueur)
    }

    private fun tranche(source: ByteBuffer, longueur: Int): ByteBuffer {
        val vue = source.slice().order(ByteOrder.LITTLE_ENDIAN)
        vue.limit(longueur)
        source.position(source.position() + longueur)
        return vue
    }

    private fun lireOctets(f: RandomAccessFile, position: Long, longueur: Int): ByteArray {
        val octets = ByteArray(longueur)
        f.seek(position)
        f.readFully(octets)
        return octets
    }

    private fun sha256(octets: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") { "%02x".format(it) }

    private const val TAILLE_FIN_REPERTOIRE = 22L
    private const val SIGNATURE_FIN_REPERTOIRE = 0x06054b50
    private const val MAGIE = "APK Sig Block 42"
    private const val ID_V2 = 0x7109871a
    private const val ID_V3 = 0xf05368c0.toInt()

    /** A signing block weighs a few KB; anything above this cap is crafted. */
    private const val TAILLE_MAX_BLOC = 16L * 1024 * 1024
}
