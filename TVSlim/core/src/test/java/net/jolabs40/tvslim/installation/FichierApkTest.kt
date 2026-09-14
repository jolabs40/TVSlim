package net.jolabs40.tvslim.installation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Le manifeste binaire, lu sur de vrais octets : ceux de l'application TV de ce dépôt telle qu'`aapt2` l'a
 * compilée — table des chaînes en UTF-16 — et une ressource du même APK, dont la table est en UTF-8.
 */
class FichierApkTest {

    private val fixtures = File("src/test/fixtures/apk")
    private val manifeste = File(fixtures, "manifeste-utf16.bin").readBytes()
    private val attendu = ManifesteApk("net.jolabs40.tvslim", versionCode = 1, versionName = "1.0.0", minSdk = 26)

    private fun archive(vararg entrees: Pair<String, ByteArray>): File {
        val fichier = Files.createTempFile("tvslim", ".apk").toFile().apply { deleteOnExit() }
        ZipOutputStream(fichier.outputStream()).use { zip ->
            entrees.forEach { (nom, octets) ->
                zip.putNextEntry(ZipEntry(nom))
                zip.write(octets)
                zip.closeEntry()
            }
        }
        return fichier
    }

    @Test
    fun `le manifeste d'un vrai APK donne son paquet, sa version et son Android minimal`() {
        assertEquals(attendu, ManifesteBinaire.lire(manifeste))
    }

    @Test
    fun `une table des chaines en UTF-8 se lit aussi`() {
        val elements = ManifesteBinaire.elements(File(fixtures, "animateur-utf8.bin").readBytes())
        val animateurs = elements.filter { it.nom == "objectAnimator" }

        assertEquals(listOf("alpha", "alpha", "scaleX", "scaleY"), animateurs.map { it.attributs["propertyName"]?.texte })
        assertEquals(66, animateurs.first().attributs["duration"]?.entier)
    }

    @Test
    fun `un APK se reconnait a son manifeste`() {
        val apk = archive("AndroidManifest.xml" to manifeste, "classes.dex" to ByteArray(16))

        assertEquals(AnalyseApk.Valide(attendu), FichierApk.analyser(apk))
    }

    @Test
    fun `une archive de plusieurs APK est un lot, pas un APK`() {
        val lot = archive(
            "base.apk" to ByteArray(8),
            "split_config.arm64_v8a.apk" to ByteArray(8),
            "toc.pb" to ByteArray(4),
        )

        assertEquals(AnalyseApk.Lot, FichierApk.analyser(lot))
    }

    @Test
    fun `un fichier qui n'est pas un APK est refuse sans exception`() {
        val texte = Files.createTempFile("tvslim", ".apk").toFile().apply {
            deleteOnExit()
            writeText("pas une archive")
        }

        assertEquals(AnalyseApk.PasUnApk, FichierApk.analyser(texte))
        assertEquals(AnalyseApk.PasUnApk, FichierApk.analyser(archive("notes.txt" to "bonjour".toByteArray())))
        assertEquals(AnalyseApk.PasUnApk, FichierApk.analyser(archive("AndroidManifest.xml" to "<manifest/>".toByteArray())))
    }

    @Test
    fun `un manifeste tronque ou corrompu ne fait pas tomber la lecture`() {
        listOf(0, 8, 64, 500, manifeste.size - 1).forEach { taille ->
            assertNull("tronqué à $taille octets", ManifesteBinaire.lire(manifeste.copyOf(taille)))
        }
        // Un morceau de taille nulle ferait tourner la lecture sur place.
        val boucle = manifeste.copyOf().also { octets -> (12..15).forEach { octets[it] = 0 } }
        assertNull(ManifesteBinaire.lire(boucle))
    }
}
