package net.jolabs40.tvslim.files

import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder listing: the command, and its output as captured on the TCL. */
class FolderReadTest {

    /** Excerpt of `/` on the TCL: folders, links to folders, and `d`, a link to `/sdcard`. */
    private val tclRoot = """
        E|41ed|27|1230768000|acct
        E|a1a4|11|1230768000|bin
        E|41f8|4096|2|cache
        E|a1a4|17|1230768000|d
        E|41f9|4096|1790426962|data
        E|a1a4|21|1230768000|init.environ.rc
        D|bin
        D|d
    """.trimIndent()

    @Test
    fun `a link to a folder is browsed like a folder`() {
        val entries = FolderReader.entries(tclRoot).associateBy { it.name }

        assertEquals(EntryKind.FOLDER, entries.getValue("bin").kind)
        assertTrue(entries.getValue("bin").link)
        assertEquals(EntryKind.FOLDER, entries.getValue("data").kind)
        assertFalse(entries.getValue("data").link)
        // A link whose target is not a folder, or does not exist, stays a file.
        assertEquals(EntryKind.FILE, entries.getValue("init.environ.rc").kind)
    }

    @Test
    fun `folders come first, then files, ignoring case`() {
        val output = """
            E|81b0|1412089|1789490078|rest.mp4
            E|45f8|4096|1751999274|Movies
            E|81b0|192902|1789249937|a.png
            E|45f9|4096|1751999271|android
            E|81b0|0|1789298639|B.png
        """.trimIndent()

        assertEquals(
            listOf("android", "Movies", "a.png", "B.png", "rest.mp4"),
            FolderReader.entries(output).map { it.name },
        )
    }

    @Test
    fun `size and date are read, the date in milliseconds`() {
        val video = FolderReader.entries("E|81b0|1412089|1789490078|rest.mp4").single()

        assertEquals(EntryKind.FILE, video.kind)
        assertEquals(1_412_089L, video.size)
        assertEquals(1_789_490_078_000L, video.date)
    }

    @Test
    fun `a name keeps its spaces and vertical bars`() {
        val entry = FolderReader.entries("E|81b0|10|1|Film | partie 2 .mkv").single()

        assertEquals("Film | partie 2 .mkv", entry.name)
    }

    @Test
    fun `anything neither folder nor file is other, and stray lines are ignored`() {
        val output = """
            stat: '.*': No such file or directory
            E|21b6|0|1|null
            E|41ed|4096|1|.
            E|41ed|4096|1|..
            E|zz|1|1|illisible
        """.trimIndent()

        assertEquals(listOf("null" to EntryKind.OTHER), FolderReader.entries(output).map { it.name to it.kind })
    }

    @Test
    fun `the command exit codes mean not found, denied or failed`() {
        assertEquals(FolderRead.NotFound("/x"), FolderReader.read("/x", ShellResult(2, "")))
        assertEquals(FolderRead.Rejected("/data"), FolderReader.read("/data", ShellResult(3, "")))
        assertEquals(
            FolderRead.Failed("/sdcard", "Aucun téléviseur connecté."),
            FolderReader.read("/sdcard", ShellResult.unavailable("Aucun téléviseur connecté.")),
        )
        assertEquals(FolderRead.Read("/vide", emptyList()), FolderReader.read("/vide", ShellResult(0, "")))
    }

    @Test
    fun `the path is quoted in the command`() {
        val command = FolderReader.command("/sdcard/l'été")

        assertTrue(command.startsWith("[ -d '/sdcard/l'\\''été' ] || exit 2; cd '/sdcard/l'\\''été' "))
        assertTrue("stat -c 'E|%f|%s|%Y|%n'" in command)
    }

    @Test
    fun `removable volumes are everything else under storage`() {
        assertEquals(listOf("1234-ABCD"), FolderReader.volumes("emulated\nself\n1234-ABCD\n"))
        assertEquals(emptyList<String>(), FolderReader.volumes("emulated\nself"))
    }

    @Test
    fun `volumes go between the common folders and the technical ones`() {
        val kinds = Shortcut.withVolumes(listOf("1234-ABCD")).map { it.kind }

        assertEquals(
            listOf(
                ShortcutKind.INTERNAL, ShortcutKind.DOWNLOADS, ShortcutKind.MOVIES,
                ShortcutKind.MUSIC, ShortcutKind.IMAGES, ShortcutKind.VOLUME,
                ShortcutKind.TEMPORARY, ShortcutKind.ROOT,
            ),
            kinds,
        )
        assertEquals("/storage/1234-ABCD", Shortcut.withVolumes(listOf("1234-ABCD"))[5].path)
    }
}
