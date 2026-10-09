package net.jolabs40.tvslim.files

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Browsing and uploading to the TV: what is sent, in which order, and what stops it. */
class FileBrowserTest {

    /** Fake TV: knows a few folders, records commands and uploads, and refuses the given paths. */
    private class FakeTv(
        private val folders: Map<String, String> = mapOf("/sdcard/Movies" to ""),
        private val rejectedPaths: Set<String> = emptySet(),
        private val cutAt: String? = null,
        private val mkdirResponse: ShellResult = ShellResult(0, ""),
    ) : CommandExecutor, FileUploader {
        val commands = mutableListOf<String>()
        val uploads = mutableListOf<Pair<String, String>>()
        var onSent: (String) -> Unit = {}

        override suspend fun execute(command: String): ShellResult {
            commands += command
            if (command.startsWith("mkdir -p")) return mkdirResponse
            if (command.startsWith("ls /storage")) return ShellResult(0, "emulated\nself\nUSB-STICK")
            val path = Regex("""^\[ -d '([^']*)' ]""").find(command)?.groupValues?.get(1)
            if (path != null) return folders[path]?.let { ShellResult(0, it) } ?: ShellResult(2, "")
            if (command.contains("mkdir '")) return ShellResult(0, "")
            return ShellResult(127, "unknown")
        }

        override suspend fun send(
            source: InputStream,
            size: Long,
            path: String,
            date: Long,
            cancelled: () -> Boolean,
            onSent: (sent: Long) -> Unit,
        ): ShellResult {
            val content = source.use { String(it.readBytes()) }
            this.onSent(path)
            if (cancelled()) return ShellResult.unavailable("cancelled")
            if (path == cutAt) return ShellResult.unavailable("Connection reset")
            if (path in rejectedPaths) return ShellResult(1, "couldn't create file: Permission denied")
            onSent(size)
            uploads += path to content
            return ShellResult(0, "")
        }
    }

    private class FileItem(override val path: String, private val content: String = path) : LocalFile {
        override val size: Long = content.length.toLong()
        override val date: Long = 0L
        override fun open(): InputStream = ByteArrayInputStream(content.toByteArray())
    }

    private class Unreadable(override val path: String) : LocalFile {
        override val size = 10L
        override val date = 0L
        override fun open(): InputStream = throw IOException("Access denied")
    }

    private fun plan(batch: LocalBatch, destination: String = "/sdcard/Movies") = UploadPlan(destination, batch, emptyList())

    @Test
    fun `an uploaded folder creates its folders, empty ones included, before its files`() = runTest {
        val tv = FakeTv()
        val batch = LocalBatch(
            files = listOf(FileItem("Holidays/2024/beach.jpg"), FileItem("Holidays/notes.txt")),
            folders = listOf("Holidays", "Holidays/2024", "Holidays/empty"),
        )

        val result = FileBrowser(tv, tv).upload(plan(batch))

        assertTrue(result.complete)
        assertEquals(
            "mkdir -p '/sdcard/Movies/Holidays' '/sdcard/Movies/Holidays/2024' '/sdcard/Movies/Holidays/empty'",
            tv.commands.single(),
        )
        assertEquals(
            listOf("/sdcard/Movies/Holidays/2024/beach.jpg", "/sdcard/Movies/Holidays/notes.txt"),
            tv.uploads.map { it.first },
        )
        assertEquals("Holidays/notes.txt", tv.uploads[1].second)
    }

    @Test
    fun `a refused file does not stop the next ones`() = runTest {
        val tv = FakeTv(rejectedPaths = setOf("/sdcard/Movies/b.mkv"))
        val batch = LocalBatch(listOf(FileItem("a.mkv"), FileItem("b.mkv"), Unreadable("c.mkv"), FileItem("d.mkv")))

        val result = FileBrowser(tv, tv).upload(plan(batch))

        assertEquals(2, result.sentCount)
        assertEquals(4, result.count)
        assertEquals(
            listOf(
                UploadFailure("b.mkv", "couldn't create file: Permission denied"),
                UploadFailure("c.mkv", "Access denied"),
            ),
            result.failures,
        )
        assertFalse(result.complete)
        // No folder to create, so no command is sent, only the files.
        assertTrue(tv.commands.isEmpty())
    }

    @Test
    fun `a lost connection stops everything`() = runTest {
        val tv = FakeTv(cutAt = "/sdcard/Movies/b.mkv")
        val batch = LocalBatch(listOf(FileItem("a.mkv"), FileItem("b.mkv"), FileItem("c.mkv")))

        val result = FileBrowser(tv, tv).upload(plan(batch))

        assertTrue(result.interrupted)
        assertEquals(1, result.sentCount)
        assertEquals(listOf("/sdcard/Movies/a.mkv"), tv.uploads.map { it.first })
    }

    @Test
    fun `cancelling stops the current upload and the next ones`() = runTest {
        val tv = FakeTv()
        var cancelled = false
        tv.onSent = { path -> if (path.endsWith("b.mkv")) cancelled = true }
        val batch = LocalBatch(listOf(FileItem("a.mkv"), FileItem("b.mkv"), FileItem("c.mkv")))

        val result = FileBrowser(tv, tv).upload(plan(batch), cancelled = { cancelled })

        assertTrue(result.cancelled)
        assertFalse(result.interrupted)
        assertEquals(1, result.sentCount)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `folders that cannot be created stop the upload before the first file`() = runTest {
        val tv = FakeTv(mkdirResponse = ShellResult(1, "mkdir: '/x/a': Permission denied"))
        val batch = LocalBatch(listOf(FileItem("a/b.txt")))

        val result = FileBrowser(tv, tv).upload(plan(batch, "/x"))

        assertEquals(listOf(UploadFailure("/x", "mkdir: '/x/a': Permission denied")), result.failures)
        assertTrue(tv.uploads.isEmpty())
    }

    @Test
    fun `progress tracks bytes across the whole batch`() = runTest {
        val tv = FakeTv()
        val batch = LocalBatch(listOf(FileItem("a", "1234"), FileItem("b", "123456")))
        val progressUpdates = mutableListOf<UploadProgress>()

        FileBrowser(tv, tv).upload(plan(batch), onProgress = { progressUpdates += it })

        assertEquals(
            listOf(
                UploadProgress("a", 1, 2, 0, 10),
                UploadProgress("a", 1, 2, 4, 10),
                UploadProgress("b", 2, 2, 4, 10),
                UploadProgress("b", 2, 2, 10, 10),
            ),
            progressUpdates,
        )
    }

    @Test
    fun `the check lists what already exists and refuses a file in place of a folder`() = runTest {
        val alreadyExists = "E|45f8|4096|1|Holidays\nE|81b0|5|1|film.mkv"
        val tv = FakeTv(folders = mapOf("/sdcard/Movies" to alreadyExists))
        val browser = FileBrowser(tv, tv)

        val ready = browser.examine(
            LocalBatch(listOf(FileItem("Holidays/a.jpg"), FileItem("film.mkv"), FileItem("new.mkv"))),
            "/sdcard/Movies/",
        )
        assertEquals(listOf("Holidays", "film.mkv"), (ready as UploadReview.Ready).plan.existing)
        assertEquals("/sdcard/Movies", ready.plan.destination)

        val rejected = browser.examine(LocalBatch(listOf(FileItem("Holidays"))), "/sdcard/Movies")
        assertEquals(UploadReview.Rejected(UploadRejection.KIND_MISMATCH, listOf("Holidays")), rejected)
    }

    @Test
    fun `the check refuses an empty batch, a broken name and an unreadable folder without sending anything`() = runTest {
        val tv = FakeTv()
        val browser = FileBrowser(tv, tv)

        assertEquals(UploadReview.Rejected(UploadRejection.EMPTY), browser.examine(LocalBatch(emptyList()), "/sdcard/Movies"))
        assertEquals(
            UploadReview.Rejected(UploadRejection.INVALID_NAME, listOf("a\nb.txt")),
            browser.examine(LocalBatch(listOf(FileItem("a\nb.txt"))), "/sdcard/Movies"),
        )
        assertEquals(
            UploadReview.Rejected(UploadRejection.UNREADABLE_DESTINATION),
            browser.examine(LocalBatch(listOf(FileItem("a.txt"))), "/absent"),
        )
        assertTrue(tv.uploads.isEmpty())
    }

    @Test
    fun `a folder is created once, and a taken name is reported`() = runTest {
        val tv = object : CommandExecutor, FileUploader {
            val commands = mutableListOf<String>()
            override suspend fun execute(command: String): ShellResult {
                commands += command
                return if (command.contains("Taken")) ShellResult(4, "") else ShellResult(0, "")
            }

            override suspend fun send(
                source: InputStream, size: Long, path: String, date: Long,
                cancelled: () -> Boolean, onSent: (sent: Long) -> Unit,
            ) = error("nothing is uploaded here")
        }
        val browser = FileBrowser(tv, tv)

        assertEquals(FolderCreation(CreationOutcome.CREATED, "Séries"), browser.createFolder("/sdcard/", " Séries "))
        assertEquals("[ -e '/sdcard/Séries' ] && exit 4; mkdir '/sdcard/Séries'", tv.commands.single())
        assertEquals(CreationOutcome.EXISTS, browser.createFolder("/sdcard", "Taken").outcome)
        assertEquals(CreationOutcome.INVALID_NAME, browser.createFolder("/sdcard", "a/b").outcome)
        assertEquals(2, tv.commands.size)
    }

    @Test
    fun `the explorer reads on start and uploads into the current folder`() = runTest {
        val tv = FakeTv(folders = mapOf("/sdcard" to "E|45f8|4096|1|Movies", "/sdcard/Movies" to ""))
        val signals = mutableListOf<FilesSignal>()
        val explorer = FileExplorer(FileBrowser(tv, tv), this, signals::add)

        explorer.start()
        advanceUntilIdle()
        assertEquals(listOf("Movies"), explorer.state.value.entries.map { it.name })
        assertEquals("/storage/USB-STICK", explorer.state.value.shortcuts.first { it.kind == ShortcutKind.VOLUME }.path)

        explorer.open("/sdcard/Movies")
        explorer.examine { LocalBatch(listOf(FileItem("film.mkv"))) }
        advanceUntilIdle()
        assertNotNull(explorer.state.value.confirmation)
        assertTrue("Nothing is sent before confirmation", tv.uploads.isEmpty())

        explorer.confirm()
        advanceUntilIdle()
        assertEquals(listOf("/sdcard/Movies/film.mkv"), tv.uploads.map { it.first })
        assertNull(explorer.state.value.progress)
        assertTrue((signals.single() as FilesSignal.Upload).result.complete)
    }

    @Test
    fun `forgetting the tv discards results from before`() = runTest {
        val tv = FakeTv(folders = mapOf("/sdcard" to "E|45f8|4096|1|Movies"))
        val explorer = FileExplorer(FileBrowser(tv, tv), this) {}

        explorer.start()
        explorer.forget()
        advanceUntilIdle()

        assertNull(explorer.state.value.reading)
        assertFalse(explorer.state.value.loading)
    }
}
