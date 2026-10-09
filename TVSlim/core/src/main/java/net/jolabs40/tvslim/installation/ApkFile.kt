package net.jolabs40.tvslim.installation

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** What a file offered for installation turned out to be. */
sealed interface ApkAnalysis {
    data class Valid(val manifest: ApkManifest) : ApkAnalysis

    /** Several APKs in one archive (`.apks`, `.xapk`, `.apkm`) that would have to be installed together. */
    data object Batch : ApkAnalysis

    /** Not an archive, no manifest, or an unreadable manifest. */
    data object NotAnApk : ApkAnalysis
}

/**
 * Inspects a local file before upload: an APK is a ZIP archive whose compiled manifest names the app.
 * Nothing is sent to the TV here.
 */
object ApkFile {

    fun analyze(file: File): ApkAnalysis = runCatching {
        ZipFile(file).use { archive ->
            val manifest = archive.getEntry(MANIFEST_NAME)
            when {
                manifest != null -> archive.getInputStream(manifest)
                    .use { readAtMost(it, MAX_MANIFEST_SIZE) }
                    ?.let(BinaryManifest::read)
                    ?.let { ApkAnalysis.Valid(it) }
                    ?: ApkAnalysis.NotAnApk

                archive.entries().asSequence().any { it.name.endsWith(".apk", ignoreCase = true) } -> ApkAnalysis.Batch
                else -> ApkAnalysis.NotAnApk
            }
        }
    }.getOrDefault(ApkAnalysis.NotAnApk)

    /** The size an archive declares can be forged, so reading stops past the cap. */
    private fun readAtMost(stream: InputStream, cap: Int): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val block = ByteArray(8 * 1024)
        while (buffer.size() <= cap) {
            val readResult = stream.read(block)
            if (readResult < 0) return buffer.toByteArray()
            buffer.write(block, 0, readResult)
        }
        return null
    }

    private const val MANIFEST_NAME = "AndroidManifest.xml"

    /** A manifest weighs a few tens of KB; 4 MB leaves headroom. */
    private const val MAX_MANIFEST_SIZE = 4 * 1024 * 1024
}
