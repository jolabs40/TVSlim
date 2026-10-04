package net.jolabs40.tvslim.windows.ecran

import net.jolabs40.tvslim.windows.maj.ClientGithub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ce que TV Slim demande à scrcpy pour le miroir, où il le cherche, et comment il installe celui qu'il télécharge. Lancer scrcpy
 * pour de vrai demande un téléviseur : ce test s'en tient à ce qui se vérifie sans lui.
 */
class ScrcpyTest {

    @Test
    fun `le miroir laisse le son au televiseur`() {
        assertEquals(
            listOf("--tcpip=192.168.2.135:5555", "--window-title=TV Slim - Miroir - TCL", "--no-audio"),
            ArgumentsScrcpy.miroir("192.168.2.135", 5555, "TV Slim - Miroir - TCL"),
        )
    }

    @Test
    fun `une version trop ancienne de scrcpy est ignoree`() {
        assertEquals(4 to 1, LocalisationScrcpy.lireLigneVersion("scrcpy 4.1 <https://github.com/Genymobile/scrcpy>\n"))
        assertNull(LocalisationScrcpy.lireLigneVersion("'scrcpy' n'est pas reconnu"))
        assertTrue(LocalisationScrcpy.suffisante(4 to 1))
        assertTrue(LocalisationScrcpy.suffisante(2 to 0))
        assertFalse(LocalisationScrcpy.suffisante(1 to 25))
    }

    @Test
    fun `la copie telechargee passe avant le PATH, puis winget`() {
        val racine = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            val telecharge = File(racine, "local/scrcpy").apply { mkdirs() }
            val chemin = File(racine, "outils").apply { mkdirs() }
            val winget = File(racine, "appdata/Microsoft/WinGet/Packages/Genymobile.scrcpy_x/scrcpy-win64-v3.3").apply { mkdirs() }
            File(chemin, "scrcpy.exe").writeText("ancien")
            File(winget, "scrcpy.exe").writeText("winget")
            val environnement = mapOf("PATH" to "C:\\absent;\"${chemin.path}\"", "LOCALAPPDATA" to File(racine, "appdata").path)
            val versions = mapOf("ancien" to (1 to 24), "winget" to (3 to 3), "epingle" to (4 to 1))
            val localisation = LocalisationScrcpy(telecharge, environnement::get) { versions[it.readText()] }

            // L'exécutable du PATH est trop ancien : celui de winget est retenu.
            assertEquals(File(winget, "scrcpy.exe"), localisation.trouver())

            // Une fois la version épinglée téléchargée, c'est elle.
            File(telecharge, "${ScrcpyEpingle.DOSSIER}/scrcpy.exe").apply { parentFile.mkdirs() }.writeText("epingle")
            assertEquals(File(telecharge, "${ScrcpyEpingle.DOSSIER}/scrcpy.exe"), localisation.trouver())
        } finally {
            racine.deleteRecursively()
        }
    }

    @Test
    fun `l'archive se decompresse sous son nom final, et une entree hors du dossier est refusee`() {
        val racine = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            val installation = InstallationScrcpy(ClientGithub("Genymobile/scrcpy", "test"), racine)
            val archive = zip(
                File(racine, "bonne.zip"),
                "${ScrcpyEpingle.DOSSIER}/scrcpy.exe" to "exe",
                "${ScrcpyEpingle.DOSSIER}/adb.exe" to "adb",
            )

            val exe = installation.decompresser(archive)

            assertEquals(File(racine, "${ScrcpyEpingle.DOSSIER}/scrcpy.exe"), exe)
            assertEquals("adb", File(racine, "${ScrcpyEpingle.DOSSIER}/adb.exe").readText())
            assertFalse(File(racine, "${ScrcpyEpingle.DOSSIER}.extraction").exists())

            val piegee = zip(File(racine, "piegee.zip"), "../evade.txt" to "x")
            assertThrows(IOException::class.java) { installation.decompresser(piegee) }
            assertFalse(File(racine.parentFile, "evade.txt").exists())
        } finally {
            racine.deleteRecursively()
        }
    }

    @Test
    fun `l'empreinte est celle de sha256sum`() {
        val fichier = Files.createTempFile("tvslim", ".txt").toFile()
        try {
            fichier.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                InstallationScrcpy.empreinte(fichier),
            )
        } finally {
            fichier.delete()
        }
    }

    private fun zip(cible: File, vararg entrees: Pair<String, String>): File {
        ZipOutputStream(cible.outputStream()).use { zip ->
            entrees.forEach { (nom, contenu) ->
                zip.putNextEntry(ZipEntry(nom))
                zip.write(contenu.toByteArray())
                zip.closeEntry()
            }
        }
        return cible
    }
}
