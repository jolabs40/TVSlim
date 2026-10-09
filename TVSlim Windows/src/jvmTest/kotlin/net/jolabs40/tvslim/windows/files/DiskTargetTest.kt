package net.jolabs40.tvslim.windows.files

import net.jolabs40.tvslim.windows.files.DiskTarget.Companion.windowsName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Local copy target: names Windows accepts, and never a partial file in place of the real one. */
class DiskTargetTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an Android name becomes a Windows name`() {
        assertEquals("Capture 12_30_05.png", windowsName("Capture 12:30:05.png"))
        assertEquals("a_b_c_d_e_f_g_h", windowsName("a<b>c\"d\\e|f?g*h"))
        assertEquals("end", windowsName("end. ."))
        assertEquals("_", windowsName("..."))
        assertEquals("_CON", windowsName("CON"))
        assertEquals("_nul.txt", windowsName("nul.txt"))
        assertEquals("_COM1.log", windowsName("COM1.log"))
        assertEquals("CONSOLE.txt", windowsName("CONSOLE.txt"))
        assertEquals("l'été 2024.mkv", windowsName("l'été 2024.mkv"))
        assertEquals("a_b", windowsName("a\tb"))
    }

    @Test
    fun `a file lands only when complete, with the TV's timestamp`() {
        val root = folder.newFolder("copies")
        val target = DiskTarget(root)
        target.createFolder("Movies/Season 1")

        target.write("Movies/Season 1/e01:final.mkv").use { writing ->
            writing.stream.write("image".toByteArray())
            assertFalse("Nothing in place before the commit", File(root, "Movies/Season 1/e01_final.mkv").exists())
            writing.commit(1_700_000_000_000L)
        }

        val arrived = File(root, "Movies/Season 1/e01_final.mkv")
        assertEquals("image", arrived.readText())
        assertEquals(1_700_000_000_000L, arrived.lastModified())
        assertTrue(target.exists("Movies/Season 1/e01:final.mkv"))
        assertEquals(listOf("e01_final.mkv"), File(root, "Movies/Season 1").list()!!.toList())
        assertEquals(File(root, "Movies").path, target.describe("Movies"))
    }

    @Test
    fun `a stopped copy leaves nothing and does not overwrite the previous file`() {
        val root = folder.newFolder("copies")
        File(root, "movie.mkv").writeText("old")
        val target = DiskTarget(root)

        target.write("movie.mkv").use { it.stream.write("partial".toByteArray()) }

        assertEquals("old", File(root, "movie.mkv").readText())
        assertEquals(listOf("movie.mkv"), root.list()!!.toList())

        target.write("movie.mkv").use { writing ->
            writing.stream.write("new".toByteArray())
            writing.commit(0L)
        }
        assertEquals("new", File(root, "movie.mkv").readText())
    }

    @Test(expected = IOException::class)
    fun `a file does not replace a folder`() {
        val root = folder.newFolder("copies")
        File(root, "Movies").mkdirs()
        DiskTarget(root).write("Movies")
    }

    @Test(expected = IOException::class)
    fun `a folder is not created in place of a file`() {
        val root = folder.newFolder("copies")
        File(root, "Movies").writeText("x")
        DiskTarget(root).createFolder("Movies/Season 1")
    }
}
