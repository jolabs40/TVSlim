package net.jolabs40.tvslim.installation

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** What a file offered for installation turned out to be. */
sealed interface AnalyseApk {
    data class Valide(val manifeste: ManifesteApk) : AnalyseApk

    /** Several APKs in one archive (`.apks`, `.xapk`, `.apkm`) that would have to be installed together. */
    data object Lot : AnalyseApk

    /** Not an archive, no manifest, or an unreadable manifest. */
    data object PasUnApk : AnalyseApk
}

/**
 * Inspects a local file before upload: an APK is a ZIP archive whose compiled manifest names the app.
 * Nothing is sent to the TV here.
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

    /** The size an archive declares can be forged, so reading stops past the cap. */
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

    /** A manifest weighs a few tens of KB; 4 MB leaves headroom. */
    private const val TAILLE_MAX_MANIFESTE = 4 * 1024 * 1024
}
