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
        assertEquals("fin", windowsName("fin. ."))
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
        target.createFolder("Films/Saison 1")

        target.write("Films/Saison 1/e01:final.mkv").use { writing ->
            writing.stream.write("image".toByteArray())
            assertFalse("Rien à sa place avant la validation", File(root, "Films/Saison 1/e01_final.mkv").exists())
            writing.commit(1_700_000_000_000L)
        }

        val arrived = File(root, "Films/Saison 1/e01_final.mkv")
        assertEquals("image", arrived.readText())
        assertEquals(1_700_000_000_000L, arrived.lastModified())
        assertTrue(target.exists("Films/Saison 1/e01:final.mkv"))
        assertEquals(listOf("e01_final.mkv"), File(root, "Films/Saison 1").list()!!.toList())
        assertEquals(File(root, "Films").path, target.describe("Films"))
    }

    @Test
    fun `a stopped copy leaves nothing and does not overwrite the previous file`() {
        val root = folder.newFolder("copies")
        File(root, "film.mkv").writeText("ancien")
        val target = DiskTarget(root)

        target.write("film.mkv").use { it.stream.write("nouv".toByteArray()) }

        assertEquals("ancien", File(root, "film.mkv").readText())
        assertEquals(listOf("film.mkv"), root.list()!!.toList())

        target.write("film.mkv").use { writing ->
            writing.stream.write("nouveau".toByteArray())
            writing.commit(0L)
        }
        assertEquals("nouveau", File(root, "film.mkv").readText())
    }

    @Test(expected = IOException::class)
    fun `a file does not replace a folder`() {
        val root = folder.newFolder("copies")
        File(root, "Films").mkdirs()
        DiskTarget(root).write("Films")
    }

    @Test(expected = IOException::class)
    fun `a folder is not created in place of a file`() {
        val root = folder.newFolder("copies")
        File(root, "Films").writeText("x")
        DiskTarget(root).createFolder("Films/Saison 1")
    }
}
