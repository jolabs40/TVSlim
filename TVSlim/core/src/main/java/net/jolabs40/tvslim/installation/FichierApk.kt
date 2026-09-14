package net.jolabs40.tvslim.installation

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** Ce qu'est un fichier qu'on propose d'installer. */
sealed interface AnalyseApk {
    data class Valide(val manifeste: ManifesteApk) : AnalyseApk

    /** Plusieurs APK dans une archive — `.apks`, `.xapk`, `.apkm` : il faudrait les envoyer ensemble. */
    data object Lot : AnalyseApk

    /** Pas une archive, pas de manifeste, ou un manifeste illisible. */
    data object PasUnApk : AnalyseApk
}

/**
 * Examine un fichier sur le disque avant qu'il ne parte : un APK est une archive ZIP dont le manifeste
 * compilé nomme l'application. Rien n'est envoyé au téléviseur ici.
 */
object FichierApk {

    fun analyser(fichier: File): AnalyseApk = runCatching {
        ZipFile(fichier).use { archive ->
            val manifeste = archive.getEntry(NOM_MANIFESTE)
            when {
                manifeste != null -> archive.getInputStream(manifeste)
                    .use { lireAuPlus(it, TAILLE_MAX_MANIFESTE) }
                    ?.let(ManifesteBinaire::lire)
                    ?.let { AnalyseApk.Valide(it) }
                    ?: AnalyseApk.PasUnApk

                archive.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) } -> AnalyseApk.Lot
                else -> AnalyseApk.PasUnApk
            }
        }
    }.getOrDefault(AnalyseApk.PasUnApk)

    /** La taille qu'annonce une archive se fabrique : on cesse de lire au-delà du plafond. */
    private fun lireAuPlus(flux: InputStream, plafond: Int): ByteArray? {
        val tampon = ByteArrayOutputStream()
        val bloc = ByteArray(8 * 1024)
        while (tampon.size() <= plafond) {
            val lus = flux.read(bloc)
            if (lus < 0) return tampon.toByteArray()
            tampon.write(bloc, 0, lus)
        }
        return null
    }

    private const val NOM_MANIFESTE = "AndroidManifest.xml"

    /** Un manifeste pèse quelques dizaines de kilo-octets ; quatre mégaoctets laissent de la marge. */
    private const val TAILLE_MAX_MANIFESTE = 4 * 1024 * 1024
}
