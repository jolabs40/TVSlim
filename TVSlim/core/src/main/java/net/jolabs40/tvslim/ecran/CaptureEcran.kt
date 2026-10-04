package net.jolabs40.tvslim.ecran

import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.shell.LecteurBinaire
import net.jolabs40.tvslim.shell.SortieBinaire
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Pourquoi une capture n'a pas abouti — chaque application le dit dans sa langue. */
enum class CauseCapture {
    /** Aucune session, ou la connexion a lâché pendant la lecture. */
    CONNEXION,

    /** `screencap` a répondu par un code d'erreur. */
    REFUSEE,

    /** La sortie n'est pas un PNG : un Android qui ne connaît pas `-p`, ou une sortie tronquée. */
    ILLISIBLE,
}

sealed interface ResultatCapture {
    class Reussie(val png: ByteArray, val largeur: Int, val hauteur: Int) : ResultatCapture

    data class Echouee(val cause: CauseCapture, val detail: String) : ResultatCapture
}

/**
 * La capture de l'écran du téléviseur, comme `adb exec-out screencap -p` : le PNG arrive par la sortie standard,
 * sans rien écrire sur le téléviseur.
 *
 * Ce que protège un DRM — Netflix, la plupart des chaînes — sort en noir : c'est le téléviseur qui le décide, et
 * rien ne permet de le distinguer d'un écran réellement noir.
 */
class CaptureEcran(private val lecteur: LecteurBinaire) {

    suspend fun capturer(): ResultatCapture {
        val sortie = lecteur.lireBinaire(COMMANDE)
        val code = sortie.code ?: return ResultatCapture.Echouee(CauseCapture.CONNEXION, sortie.motif)
        if (code != 0) {
            return ResultatCapture.Echouee(CauseCapture.REFUSEE, sortie.erreurs.ifBlank { "code $code" }.trim())
        }
        val (largeur, hauteur) = dimensions(sortie.octets)
            ?: return ResultatCapture.Echouee(CauseCapture.ILLISIBLE, debut(sortie))
        return ResultatCapture.Reussie(sortie.octets, largeur, hauteur)
    }

    private fun debut(sortie: SortieBinaire): String = sortie.erreurs.ifBlank {
        String(sortie.octets.copyOf(minOf(sortie.octets.size, 120)), Charsets.UTF_8)
    }.trim()

    companion object {
        const val COMMANDE = "screencap -p"

        private val SIGNATURE_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        private val HORODATAGE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        /**
         * Largeur et hauteur d'un PNG, lues dans son en-tête `IHDR` ; `null` si ce n'en est pas un. Un PNG
         * qu'un shell aurait traduit — `\n` devenu `\r\n` — échoue ici, sur sa signature.
         */
        fun dimensions(octets: ByteArray): Pair<Int, Int>? {
            if (octets.size < 24) return null
            if (!octets.copyOf(8).contentEquals(SIGNATURE_PNG)) return null
            if (String(octets, 12, 4, Charsets.US_ASCII) != "IHDR") return null
            val largeur = entier(octets, 16)
            val hauteur = entier(octets, 20)
            return if (largeur > 0 && hauteur > 0) largeur to hauteur else null
        }

        /**
         * Le nom d'une capture ou d'une vidéo : l'appareil et l'instant, sans rien que Windows ou Android
         * refusent dans un nom de fichier — « TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-15-30.png ».
         */
        fun nomFichier(infos: InfosAppareil, instant: LocalDateTime, extension: String): String =
            "TVSlim-${infos.nomPourFichier.take(60)}-${instant.format(HORODATAGE)}.$extension"

        private fun entier(octets: ByteArray, depart: Int): Int =
            (octets[depart].toInt() and 0xFF shl 24) or
                (octets[depart + 1].toInt() and 0xFF shl 16) or
                (octets[depart + 2].toInt() and 0xFF shl 8) or
                (octets[depart + 3].toInt() and 0xFF)
    }
}
