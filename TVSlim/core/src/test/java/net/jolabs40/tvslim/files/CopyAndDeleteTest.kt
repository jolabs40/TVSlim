package net.jolabs40.tvslim.files

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.FileReceiver
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Copying from the TV to the computer, and deleting on the TV. */
class CopyAndDeleteTest {

    /** Fake TV: file contents, one listing per folder, and delete commands recorded without deleting anything. */
    private class FakeTv(
        private val contents: Map<String, String> = emptyMap(),
        private val inventories: Map<String, ShellResult> = emptyMap(),
        private val rejectedPaths: Set<String> = emptySet(),
        private val cutAt: String? = null,
        private val rmResponse: ShellResult = ShellResult(0, ""),
    ) : CommandExecutor, FileUploader, FileReceiver {
        val commands = mutableListOf<String>()
        val receivedBytes = mutableListOf<String>()
        var onReceive: (String) -> Unit = {}

        override suspend fun execute(command: String): ShellResult {
            commands += command
            if (command.contains("rm -")) return rmResponse
            val path = Regex("""\[ -d '([^']*)' ] \|\| exit 2; cd""").find(command)?.groupValues?.get(1)
            return path?.let { inventories[it] } ?: ShellResult(2, "")
        }

        override suspend fun send(
            source: InputStream, size: Long, path: String, date: Long,
            cancelled: () -> Boolean, onSent: (sent: Long) -> Unit,
        ) = error("rien ne s'envoie")

        override suspend fun receive(
            path: String, destination: OutputStream, size: Long,
            cancelled: () -> Boolean, onReceived: (received: Long) -> Unit,
        ): ShellResult {
            onReceive(path)
            if (cancelled()) return ShellResult.unavailable("annulé")
            if (path == cutAt) return ShellResult.unavailable("Connection reset")
            if (path in rejectedPaths) return ShellResult(1, "open failed: Permission denied")
            val content = contents[path] ?: return ShellResult(1, "open failed: No such file or directory")
            // Bytes are written before the outcome is known: nothing unvalidated may land on disk.
            destination.write(content.toByteArray())
            onReceived(content.length.toLong())
            receivedBytes += path
            return ShellResult(0, "")
        }
    }

    /** In-memory local folder: only validated files land in it. */
    private class FakeDisk(
        val existing: Set<String> = emptySet(),
        private val rejectedPaths: Set<String> = emptySet(),
    ) : LocalTarget {
        val folders = mutableListOf<String>()
        val files = linkedMapOf<String, Pair<String, Long>>()
        var opened = 0
        var closed = 0

        override fun describe(path: String) = if (path.isEmpty()) "C:\\Copies" else "C:\\Copies\\" + path.replace('/', '\\')
        override fun exists(path: String) = path in existing
        override fun createFolder(path: String) {
            if (path in rejectedPaths) throw IOException("Accès refusé")
            folders += path
        }

        override fun write(path: String): LocalWrite {
            if (path in rejectedPaths) throw IOException("Nom de fichier incorrect")
            opened++
            return object : LocalWrite {
                override val stream = ByteArrayOutputStream()
                override fun commit(date: Long) {
                    files[path] = stream.toString(Charsets.UTF_8) to date
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private val movies = RemoteEntry("Films", EntryKind.FOLDER, 4096, 0L)

    /** What the TCL returns for a folder: `.` first, subfolders, an empty one, names with spaces and `|`. */
    private val moviesInventory = ShellResult(
        0,
        """
            D|0|1790000000|.
            D|0|1790000000|./Séries/Saison 1
            D|0|1790000000|./Séries
            D|0|1790000000|./vide
            F|12|1790000001|./bande|annonce.mp4
            F|3|1790000002|./Séries/Saison 1/e01.mkv
            find: ./perdu: Permission denied
        """.trimIndent(),
    )

    @Test
    fun `the listing puts parent folders first and leaves out the folder itself`() {
        val inventory = FolderInventory.inventory(moviesInventory.output)

        assertEquals(listOf("Séries", "vide", "Séries/Saison 1"), inventory.folders)
        assertEquals(
            listOf(
                InventoryFile("bande|annonce.mp4", 12, 1_790_000_001_000L),
                InventoryFile("Séries/Saison 1/e01.mkv", 3, 1_790_000_002_000L),
            ),
            inventory.files,
        )
        assertEquals(15L, inventory.size)
    }

    @Test
    fun `commands quote their paths, and only a folder gets the storage guard`() {
        assertEquals("rm -f '/sdcard/l'\\''été.mkv'", FolderInventory.deletionCommand("/sdcard/l'été.mkv", DeletionKind.FILE))
        assertEquals("rm -f '/data/local/tmp/lien'", FolderInventory.deletionCommand("/data/local/tmp/lien", DeletionKind.LINK))

        val folder = FolderInventory.deletionCommand("/sdcard/Films", DeletionKind.FOLDER)
        assertTrue(folder.startsWith("c=\$(readlink -f '/sdcard/Films');"))
        assertTrue(folder.contains("/storage/emulated/0 /storage/self/primary"))
        assertTrue(folder.endsWith("exit 5;; esac; done; rm -rf '/sdcard/Films'"))

        assertFalse(FolderInventory.command("/sdcard/Films", guard = false).contains("readlink"))
        assertTrue(FolderInventory.command("/sdcard/Films", guard = true).startsWith("c=\$(readlink -f '/sdcard/Films');"))
    }

    @Test
    fun `a file is copied under the chosen name without any extra read`() = runTest {
        val tv = FakeTv(contents = mapOf("/sdcard/Movies/film.mkv" to "image"))
        val disk = FakeDisk()
        val browser = FileBrowser(tv, tv, tv)
        val entry = RemoteEntry("film.mkv", EntryKind.FILE, 5, 1_000L)

        val plan = (browser.prepareDownload("/sdcard/Movies/", entry, disk, "copie.mkv") as DownloadReview.Ready).plan
        assertTrue(tv.commands.isEmpty())
        assertEquals("C:\\Copies", plan.destination)

        val result = browser.download(plan)

        assertTrue(result.complete)
        assertEquals(TransferDirection.DOWNLOAD, result.direction)
        assertEquals(mapOf("copie.mkv" to ("image" to 1_000L)), disk.files)
        assertTrue(disk.folders.isEmpty())
    }

    @Test
    fun `a folder is listed in full, then arrives with its empty folders`() = runTest {
        val tv = FakeTv(
            contents = mapOf(
                "/sdcard/Films/bande|annonce.mp4" to "bande-annonce",
                "/sdcard/Films/Séries/Saison 1/e01.mkv" to "e01",
            ),
            inventories = mapOf("/sdcard/Films" to moviesInventory),
        )
        val disk = FakeDisk(existing = setOf("Films"))
        val browser = FileBrowser(tv, tv, tv)

        val plan = (browser.prepareDownload("/sdcard", movies, disk, "Films") as DownloadReview.Ready).plan

        assertTrue(plan.alreadyExists)
        assertEquals("C:\\Copies\\Films", plan.destination)
        assertEquals(listOf("Films", "Films/Séries", "Films/vide", "Films/Séries/Saison 1"), plan.folders)
        assertEquals(
            listOf("/sdcard/Films/bande|annonce.mp4", "/sdcard/Films/Séries/Saison 1/e01.mkv"),
            plan.files.map { it.remote },
        )

        val result = browser.download(plan)

        assertTrue(result.complete)
        assertEquals(plan.folders, disk.folders)
        assertEquals(listOf("Films/bande|annonce.mp4", "Films/Séries/Saison 1/e01.mkv"), disk.files.keys.toList())
        assertEquals("C:\\Copies\\Films", result.destination)
    }

    @Test
    fun `an unreadable folder is not copied`() = runTest {
        val tv = FakeTv(inventories = mapOf("/data" to ShellResult(3, "")))
        val browser = FileBrowser(tv, tv, tv)
        val data = RemoteEntry("data", EntryKind.FOLDER, 4096, 0L)

        assertEquals(DownloadReview.Unreadable(ReadRejection.DENIED), browser.prepareDownload("/", data, FakeDisk(), "data"))
        assertEquals(
            DownloadReview.Unreadable(ReadRejection.NOT_FOUND),
            browser.prepareDownload("/sdcard", movies, FakeDisk(), "Films"),
        )
    }

    @Test
    fun `a refusal from the tv or the disk does not stop the next files, and nothing partial arrives`() = runTest {
        val tv = FakeTv(
            contents = mapOf("/sdcard/a" to "a", "/sdcard/c" to "c", "/sdcard/d" to "d"),
            rejectedPaths = setOf("/sdcard/b"),
        )
        val disk = FakeDisk(rejectedPaths = setOf("c"))
        val plan = DownloadPlan(
            source = "/sdcard",
            target = disk,
            name = "x",
            folder = false,
            files = listOf("a", "b", "c", "d").map { RemoteFile("/sdcard/$it", it, 1, 0L) },
        )

        val result = FileBrowser(tv, tv, tv).download(plan)

        assertEquals(2, result.sentCount)
        assertEquals(
            listOf(UploadFailure("b", "open failed: Permission denied"), UploadFailure("c", "Nom de fichier incorrect")),
            result.failures,
        )
        assertEquals(listOf("a", "d"), disk.files.keys.toList())
        assertEquals("Chaque écriture ouverte est refermée", disk.opened, disk.closed)
    }

    @Test
    fun `a lost connection stops the copy, and so does cancelling`() = runTest {
        val contents = mapOf("/sdcard/a" to "a", "/sdcard/b" to "b", "/sdcard/c" to "c")
        val files = listOf("a", "b", "c").map { RemoteFile("/sdcard/$it", it, 1, 0L) }

        val cut = FakeDisk()
        val cutOffTv = FakeTv(contents = contents, cutAt = "/sdcard/b")
        val interrupted = FileBrowser(cutOffTv, cutOffTv, cutOffTv)
            .download(DownloadPlan("/sdcard", cut, "x", false, files))
        assertTrue(interrupted.interrupted)
        assertEquals(listOf("a"), cut.files.keys.toList())

        val stopped = FakeDisk()
        val tv = FakeTv(contents = contents)
        var cancelled = false
        tv.onReceive = { if (it.endsWith("b")) cancelled = true }
        val cancelledCopy = FileBrowser(tv, tv, tv)
            .download(DownloadPlan("/sdcard", stopped, "x", false, files), cancelled = { cancelled })
        assertTrue(cancelledCopy.cancelled)
        assertEquals(1, cancelledCopy.sentCount)
        assertEquals(listOf("a"), stopped.files.keys.toList())
    }

    @Test
    fun `a folder refused by the disk stops the copy before the first file`() = runTest {
        val tv = FakeTv(contents = mapOf("/sdcard/Films/a" to "a"))
        val disk = FakeDisk(rejectedPaths = setOf("Films"))
        val plan = DownloadPlan(
            "/sdcard/Films", disk, "Films", true,
            listOf(RemoteFile("/sdcard/Films/a", "Films/a", 1, 0L)), folders = listOf("Films"),
        )

        val result = FileBrowser(tv, tv, tv).download(plan)

        assertEquals(listOf(UploadFailure("Films", "Accès refusé")), result.failures)
        assertTrue(tv.receivedBytes.isEmpty())
    }

    @Test
    fun `deleting a folder reports its contents, deleting a link removes only the link`() = runTest {
        val tv = FakeTv(inventories = mapOf("/sdcard/Films" to moviesInventory))
        val browser = FileBrowser(tv, tv)

        val folder = browser.prepareDeletion("/sdcard", movies)
        assertEquals(
            DeletionReview.Ready(DeletionPlan("/sdcard/Films", DeletionKind.FOLDER, files = 2, folders = 3, size = 15)),
            folder,
        )
        assertTrue("La lecture monte la garde", tv.commands.single().startsWith("c=\$(readlink -f '/sdcard/Films');"))

        val link = RemoteEntry("sdcard", EntryKind.FOLDER, 21, 0L, link = true)
        assertEquals(
            DeletionReview.Ready(DeletionPlan("/sdcard", DeletionKind.LINK)),
            browser.prepareDeletion("/", link),
        )
        val file = RemoteEntry("a.mkv", EntryKind.FILE, 42, 0L)
        assertEquals(
            DeletionReview.Ready(DeletionPlan("/sdcard/a.mkv", DeletionKind.FILE, files = 1, size = 42)),
            browser.prepareDeletion("/sdcard", file),
        )
        assertEquals("Ni le lien ni le fichier ne demandent de lecture", 1, tv.commands.size)
    }

    @Test
    fun `a whole storage volume cannot be deleted`() = runTest {
        val tv = FakeTv(inventories = mapOf("/storage/emulated" to ShellResult(5, "")), rmResponse = ShellResult(5, ""))
        val browser = FileBrowser(tv, tv)
        val emulated = RemoteEntry("emulated", EntryKind.FOLDER, 4096, 0L)

        assertEquals(DeletionReview.Protected, browser.prepareDeletion("/storage", emulated))
        assertEquals(
            EntryDeletion(DeletionOutcome.PROTECTED, "emulated"),
            browser.delete(DeletionPlan("/storage/emulated", DeletionKind.FOLDER)),
        )
    }

    @Test
    fun `a refusal from the tv is reported verbatim`() = runTest {
        val tv = FakeTv(rmResponse = ShellResult(1, "rm: /system/x: Read-only file system"))

        val outcome = FileBrowser(tv, tv).delete(DeletionPlan("/system/x", DeletionKind.FILE))

        assertEquals(EntryDeletion(DeletionOutcome.FAILED, "x", "rm: /system/x: Read-only file system"), outcome)
        assertEquals("rm -f '/system/x'", tv.commands.single())
    }

    @Test
    fun `the explorer copies a file at once and a folder after confirmation`() = runTest {
        val tv = FakeTv(
            contents = mapOf("/sdcard/a.mkv" to "a", "/sdcard/Films/bande|annonce.mp4" to "b", "/sdcard/Films/Séries/Saison 1/e01.mkv" to "e"),
            inventories = mapOf("/sdcard" to ShellResult(0, ""), "/sdcard/Films" to moviesInventory),
        )
        val signals = mutableListOf<FilesSignal>()
        val explorer = FileExplorer(FileBrowser(tv, tv, tv), this, signals::add)
        val disk = FakeDisk()

        explorer.download(RemoteEntry("a.mkv", EntryKind.FILE, 1, 0L), disk, "a.mkv")
        advanceUntilIdle()
        assertEquals(listOf("a.mkv"), disk.files.keys.toList())
        assertEquals(TransferDirection.DOWNLOAD, (signals.single() as FilesSignal.Upload).result.direction)

        explorer.download(movies, disk, "Films")
        advanceUntilIdle()
        assertNotNull(explorer.state.value.pendingDownload)
        assertTrue(explorer.state.value.busy)
        assertEquals("Rien ne se copie avant la confirmation", 1, disk.files.size)

        explorer.confirmDownload()
        advanceUntilIdle()
        assertEquals(3, disk.files.size)
        assertNull(explorer.state.value.progress)
        assertFalse(explorer.state.value.busy)
        assertEquals(2, (signals.last() as FilesSignal.Upload).result.sentCount)
    }

    @Test
    fun `the explorer deletes only after confirmation, then reloads the folder`() = runTest {
        val tv = FakeTv(inventories = mapOf("/sdcard/Films" to moviesInventory))
        val signals = mutableListOf<FilesSignal>()
        val explorer = FileExplorer(FileBrowser(tv, tv), this, signals::add)

        explorer.requestDeletion(movies)
        advanceUntilIdle()
        assertEquals(2, explorer.state.value.deletion?.files)
        assertTrue(tv.commands.none { it.contains("rm -") })

        explorer.requestDeletion(movies)
        assertEquals(FilesSignal.Busy, signals.single())

        explorer.confirmDeletion()
        advanceUntilIdle()
        assertTrue(tv.commands.last { !it.startsWith("[ -d") }.endsWith("rm -rf '/sdcard/Films'"))
        assertEquals(FilesSignal.Deletion(EntryDeletion(DeletionOutcome.DELETED, "Films")), signals.last())
        assertTrue("Le dossier est relu", tv.commands.last().startsWith("[ -d '/sdcard' ]"))
        assertFalse(explorer.state.value.busy)
    }

    @Test
    fun `a deletion refused upfront asks for nothing`() = runTest {
        val tv = FakeTv(inventories = mapOf("/sdcard/Android" to ShellResult(5, "")))
        val signals = mutableListOf<FilesSignal>()
        val explorer = FileExplorer(FileBrowser(tv, tv), this, signals::add)

        explorer.requestDeletion(RemoteEntry("Android", EntryKind.FOLDER, 4096, 0L))
        advanceUntilIdle()

        assertNull(explorer.state.value.deletion)
        assertEquals(FilesSignal.Deletion(EntryDeletion(DeletionOutcome.PROTECTED, "Android")), signals.single())
    }
}
