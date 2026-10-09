package net.jolabs40.tvslim.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Binary manifest parsing on real bytes from this repo's TV app as compiled by `aapt2`: the manifest's string pool
 * is UTF-16, while another compiled resource from the same APK uses UTF-8.
 */
class ApkFileTest {

    private val fixtures = File("src/test/fixtures/apk")
    private val manifest = File(fixtures, "manifeste-utf16.bin").readBytes()
    private val expected = ApkManifest("net.jolabs40.tvslim", versionCode = 1, versionName = "1.0.0", minSdk = 26)

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = Files.createTempFile("tvslim", ".apk").toFile().apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a real APK manifest gives its package, version and minimum Android`() {
        assertEquals(expected, BinaryManifest.read(manifest))
    }

    @Test
    fun `a UTF-8 string pool is read too`() {
        val elements = BinaryManifest.elements(File(fixtures, "animateur-utf8.bin").readBytes())
        val animators = elements.filter { it.name == "objectAnimator" }

        assertEquals(listOf("alpha", "alpha", "scaleX", "scaleY"), animators.map { it.attributes["propertyName"]?.text })
        assertEquals(66, animators.first().attributes["duration"]?.integer)
    }

    @Test
    fun `an APK is recognised by its manifest`() {
        val apk = archive("AndroidManifest.xml" to manifest, "classes.dex" to ByteArray(16))

        assertEquals(ApkAnalysis.Valid(expected), ApkFile.analyze(apk))
    }

    @Test
    fun `an archive of several APKs is a bundle, not an APK`() {
        val batch = archive(
            "base.apk" to ByteArray(8),
            "split_config.arm64_v8a.apk" to ByteArray(8),
            "toc.pb" to ByteArray(4),
        )

        assertEquals(ApkAnalysis.Batch, ApkFile.analyze(batch))
    }

    @Test
    fun `a file that is not an APK is rejected without an exception`() {
        val text = Files.createTempFile("tvslim", ".apk").toFile().apply {
            deleteOnExit()
            writeText("pas une archive")
        }

        assertEquals(ApkAnalysis.NotAnApk, ApkFile.analyze(text))
        assertEquals(ApkAnalysis.NotAnApk, ApkFile.analyze(archive("notes.txt" to "bonjour".toByteArray())))
        assertEquals(ApkAnalysis.NotAnApk, ApkFile.analyze(archive("AndroidManifest.xml" to "<manifest/>".toByteArray())))
    }

    @Test
    fun `a truncated or corrupt manifest does not crash the parser`() {
        listOf(0, 8, 64, 500, manifest.size - 1).forEach { size ->
            assertNull("tronqué à $size octets", BinaryManifest.read(manifest.copyOf(size)))
        }
        // A zero-size chunk would make the parser loop forever.
        val loop = manifest.copyOf().also { bytes -> (12..15).forEach { bytes[it] = 0 } }
        assertNull(BinaryManifest.read(loop))
    }
}
