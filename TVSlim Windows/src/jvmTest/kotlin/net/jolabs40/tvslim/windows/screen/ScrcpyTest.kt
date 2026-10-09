package net.jolabs40.tvslim.windows.screen

import net.jolabs40.tvslim.windows.update.GithubClient
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
 * scrcpy mirror arguments, lookup, and installation of the downloaded copy. Running scrcpy needs a TV, so this only
 * covers what can be checked without one.
 */
class ScrcpyTest {

    @Test
    fun `the mirror leaves audio on the TV`() {
        assertEquals(
            listOf("--tcpip=192.168.2.135:5555", "--window-title=TV Slim - Mirror - TCL", "--no-audio"),
            ScrcpyArguments.mirror("192.168.2.135", 5555, "TV Slim - Mirror - TCL"),
        )
    }

    @Test
    fun `an outdated scrcpy version is ignored`() {
        assertEquals(4 to 1, ScrcpyLocator.readVersionLine("scrcpy 4.1 <https://github.com/Genymobile/scrcpy>\n"))
        assertNull(ScrcpyLocator.readVersionLine("'scrcpy' n'est pas reconnu"))
        assertTrue(ScrcpyLocator.isSufficient(4 to 1))
        assertTrue(ScrcpyLocator.isSufficient(2 to 0))
        assertFalse(ScrcpyLocator.isSufficient(1 to 25))
    }

    @Test
    fun `the downloaded copy comes before PATH, then winget`() {
        val root = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            val downloaded = File(root, "local/scrcpy").apply { mkdirs() }
            val path = File(root, "tools").apply { mkdirs() }
            val winget = File(root, "appdata/Microsoft/WinGet/Packages/Genymobile.scrcpy_x/scrcpy-win64-v3.3").apply { mkdirs() }
            File(path, "scrcpy.exe").writeText("old")
            File(winget, "scrcpy.exe").writeText("winget")
            val environment = mapOf("PATH" to "C:\\absent;\"${path.path}\"", "LOCALAPPDATA" to File(root, "appdata").path)
            val versions = mapOf("old" to (1 to 24), "winget" to (3 to 3), "pinned" to (4 to 1))
            val locator = ScrcpyLocator(downloaded, environment::get) { versions[it.readText()] }

            // The PATH executable is too old, so winget's is picked.
            assertEquals(File(winget, "scrcpy.exe"), locator.find())

            // Once the pinned version is downloaded, it wins.
            File(downloaded, "${PinnedScrcpy.FOLDER}/scrcpy.exe").apply { parentFile.mkdirs() }.writeText("pinned")
            assertEquals(File(downloaded, "${PinnedScrcpy.FOLDER}/scrcpy.exe"), locator.find())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the archive extracts under its final name, and an entry outside the folder is rejected`() {
        val root = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            val installation = ScrcpyInstallation(GithubClient("Genymobile/scrcpy", "test"), root)
            val archive = zip(
                File(root, "good.zip"),
                "${PinnedScrcpy.FOLDER}/scrcpy.exe" to "exe",
                "${PinnedScrcpy.FOLDER}/adb.exe" to "adb",
            )

            val exe = installation.extract(archive)

            assertEquals(File(root, "${PinnedScrcpy.FOLDER}/scrcpy.exe"), exe)
            assertEquals("adb", File(root, "${PinnedScrcpy.FOLDER}/adb.exe").readText())
            assertFalse(File(root, "${PinnedScrcpy.FOLDER}.extraction").exists())

            val malicious = zip(File(root, "malicious.zip"), "../escaped.txt" to "x")
            assertThrows(IOException::class.java) { installation.extract(malicious) }
            assertFalse(File(root.parentFile, "escaped.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the checksum matches sha256sum`() {
        val file = Files.createTempFile("tvslim", ".txt").toFile()
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ScrcpyInstallation.fingerprint(file),
            )
        } finally {
            file.delete()
        }
    }

    private fun zip(target: File, vararg entries: Pair<String, String>): File {
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return target
    }
}
