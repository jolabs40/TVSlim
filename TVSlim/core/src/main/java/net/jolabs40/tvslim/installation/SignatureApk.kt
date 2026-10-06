package net.jolabs40.tvslim.installation

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Le certificat qui signe un APK, lu dans son bloc de signature — schéma v3, sinon v2.
 *
 * La signature elle-même n'est pas vérifiée ici : Android le fera à l'installation, et refusera un APK
 * dont la signature ne correspond pas au certificat qu'il porte. Comparer l'empreinte de ce certificat à
 * celle qu'on attend suffit donc à n'envoyer au téléviseur que nos propres APK : un fichier substitué
 * en route porterait un autre certificat, ou une signature qu'Android rejetterait.
 *
 * Format : https://source.android.com/docs/security/features/apksigning/v2#apk-signing-block
 */
object SignatureApk {

    /** L'empreinte SHA-256 du certificat du premier signataire, en hexadécimal minuscule ; null sans bloc lisible. */
    fun empreinteCertificat(fichier: File): String? =
        runCatching { RandomAccessFile(fichier, "r").use(::lire) }.getOrNull()

    private fun lire(f: RandomAccessFile): String? {
        val taille = f.length()
        if (taille < TAILLE_FIN_REPERTOIRE) return null

        // La fin du répertoire central se cherche à rebours : un commentaire de 64 Ko au plus la suit.
        val queue = minOf(taille, TAILLE_FIN_REPERTOIRE + 0xFFFFL).toInt()
        val fin = lireOctets(f, taille - queue, queue)
        val b = ByteBuffer.wrap(fin).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (queue - TAILLE_FIN_REPERTOIRE.toInt() downTo 0).firstOrNull { b.getInt(it) == SIGNATURE_FIN_REPERTOIRE }
            ?: return null
        val debutRepertoire = b.getInt(eocd + 16).toLong() and 0xFFFFFFFFL

        // Le bloc de signature finit juste avant le répertoire central : sa taille, puis « APK Sig Block 42 ».
        if (debutRepertoire < 32) return null
        val pied = lireOctets(f, debutRepertoire - 24, 24)
        if (String(pied, 8, 16, Charsets.US_ASCII) != MAGIE) return null
        val tailleBloc = ByteBuffer.wrap(pied).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
        if (tailleBloc < 24 || tailleBloc > TAILLE_MAX_BLOC || tailleBloc + 8 > debutRepertoire) return null
        val tete = lireOctets(f, debutRepertoire - tailleBloc - 8, 8)
        if (ByteBuffer.wrap(tete).order(ByteOrder.LITTLE_ENDIAN).getLong(0) != tailleBloc) return null

        // Entre les deux tailles, des paires : longueur sur 8 octets, identifiant sur 4, valeur.
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
     * v2 et v3 commencent pareil : la suite des signataires ; dans le premier, ses données signées ;
     * dans celles-ci, les condensats puis les certificats — le premier est celui du signataire.
     */
    private fun premierCertificat(schema: ByteBuffer): ByteArray? {
        val signataires = prefixee(schema) ?: return null
        val signataire = prefixee(signataires) ?: return null
        val donnees = prefixee(signataire) ?: return null
        prefixee(donnees) ?: return null // condensats
        val certificats = prefixee(donnees) ?: return null
        val certificat = prefixee(certificats) ?: return null
        return ByteArray(certificat.remaining()).also { certificat.get(it) }
    }

    /** Une suite précédée de sa longueur sur 4 octets ; null si elle déborde de ce qui reste. */
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

    /** Un bloc de signature pèse quelques kilo-octets : au-delà, le fichier est fabriqué. */
    private const val TAILLE_MAX_BLOC = 16L * 1024 * 1024
}
