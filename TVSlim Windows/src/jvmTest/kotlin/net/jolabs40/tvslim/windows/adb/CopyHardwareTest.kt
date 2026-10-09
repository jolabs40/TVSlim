package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.files.RemoteEntry
import net.jolabs40.tvslim.files.UploadReview
import net.jolabs40.tvslim.files.DownloadReview
import net.jolabs40.tvslim.files.DeletionReview
import net.jolabs40.tvslim.files.FolderInventory
import net.jolabs40.tvslim.files.DeletionOutcome
import net.jolabs40.tvslim.files.FolderRead
import net.jolabs40.tvslim.files.EntryKind
import net.jolabs40.tvslim.files.DeletionKind
import net.jolabs40.tvslim.files.FileBrowser
import net.jolabs40.tvslim.files.DownloadPlan
import net.jolabs40.tvslim.files.DeletionPlan
import net.jolabs40.tvslim.files.quote
import net.jolabs40.tvslim.windows.Locations
import net.jolabs40.tvslim.windows.files.DiskTarget
import net.jolabs40.tvslim.windows.files.batchFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Copy to PC and delete on a real TV, through the app's ADB client and the shared core. Opt-in because it writes
 * and deletes, only in a `tvslim-copy-...` folder under Download and in `/data/local/tmp`:
 *
 *     ./gradlew jvmTest --tests "*CopyHardwareTest*" '-Phardware=192.168.2.135' -Pupload=1 --rerun
 *
 * The whole-storage guard is only exercised read-only, or with `echo` in place of `rm`: a bug would wipe the TV's
 * storage.
 */
class CopyHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")

    @Test
    fun `copy to PC, stop, delete, and the guard holds`() = runBlocking<Unit> {
        assumeTrue(
            "-Phardware=<address> -Pupload=1 to write and delete on a real TV",
            host != null && System.getProperty("tvslim.upload") != null,
        )
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connection to $host: ${client.connection.value}", client.connect(host!!))
        val browser = FileBrowser(client, client, client)
        val name = "tvslim-copy-${System.currentTimeMillis()}"
        val downloads = "/sdcard/Download"
        val trial = "$downloads/$name"
        val tempDir = "/data/local/tmp/$name"

        val local = Files.createTempDirectory("tvslim-copy").toFile()
        val source = File(local, "source/$name").apply { mkdirs() }
        File(source, "a.txt").writeText("hello")
        val big = File(source, "sub/b.bin").apply { parentFile.mkdirs(); writeBytes(Random(7).nextBytes(5_000_000)) }
        File(source, "empty").mkdirs()
        val huge = File(local, "huge.bin").apply { writeBytes(Random(3).nextBytes(60_000_000)) }
        val pc = File(local, "pc").apply { mkdirs() }

        try {
            // Seeded through the upload path, tested on its own.
            val uploadReview = browser.examine(batchFrom(listOf(source)), downloads) as UploadReview.Ready
            assertTrue(browser.upload(uploadReview.plan).complete)
            val hugePlan = browser.examine(batchFrom(listOf(huge)), trial) as UploadReview.Ready
            assertTrue(browser.upload(hugePlan.plan).complete)

            // 1. The whole folder, including a subfolder and an empty folder, under names Windows accepts.
            val folder = entry(browser, downloads, name)
            val plan = ready(browser.prepareDownload(downloads, folder, DiskTarget(pc), name))
            println("Plan: ${plan.files.size} files, ${plan.size} bytes, folders ${plan.folders}")
            assertEquals(3, plan.files.size)
            assertTrue(plan.folders.containsAll(listOf(name, "$name/sub", "$name/empty")))
            assertFalse(plan.alreadyExists)

            val start = System.currentTimeMillis()
            val copied = browser.download(plan)
            println("Copied in ${System.currentTimeMillis() - start} ms: $copied")
            assertTrue(copied.toString(), copied.complete)
            val arrived = File(pc, name)
            assertEquals("hello", File(arrived, "a.txt").readText())
            assertEquals(fingerprint(big), fingerprint(File(arrived, "sub/b.bin")))
            assertEquals(fingerprint(huge), fingerprint(File(arrived, "huge.bin")))
            assertTrue(File(arrived, "empty").isDirectory)
            val tvDate = (browser.listFolder(trial) as FolderRead.Read).entries.single { it.name == "a.txt" }.date
            assertEquals("The TV's date, to the second", tvDate / 1000, File(arrived, "a.txt").lastModified() / 1000)
            assertTrue("No temporary file", arrived.walk().none { it.name.endsWith(DiskTarget.TEMPORARY_SUFFIX) })

            // 1b. Names Android allows and Windows rejects. Shared storage rejects them too ("Operation not
            // permitted" for a `:`), so they are created in /data/local/tmp.
            val names = "$tempDir-names"
            val creation = client.execute(
                "mkdir ${quote(names)} && printf apostrophe > ${quote("$names/l'été 12:30.txt")} && printf nul > ${quote("$names/NUL.txt")}",
            )
            assertTrue(creation.toString(), creation.succeeded)
            val namesFolder = entry(browser, "/data/local/tmp", "$name-names")
            val namesPlan = ready(browser.prepareDownload("/data/local/tmp", namesFolder, DiskTarget(pc), "names"))
            assertTrue(browser.download(namesPlan).complete)
            assertEquals("apostrophe", File(pc, "names/l'été 12_30.txt").readText())
            assertEquals("nul", File(pc, "names/_NUL.txt").readText())

            // 2. Stop halfway through a large file: nothing lands, the previous copy stays, the session still works.
            val bigRemote = entry(browser, trial, "huge.bin")
            val bigPlan = ready(browser.prepareDownload(trial, bigRemote, DiskTarget(arrived), "huge.bin"))
            var cancelled = false
            val stopped = browser.download(bigPlan, cancelled = { cancelled }) { if (it.sent > 5_000_000) cancelled = true }
            println("Stopped: $stopped")
            assertTrue(stopped.cancelled)
            assertEquals("The previous file is intact", fingerprint(huge), fingerprint(File(arrived, "huge.bin")))
            assertTrue(arrived.walk().none { it.name.endsWith(DiskTarget.TEMPORARY_SUFFIX) })
            assertEquals(ConnectionState.CONNECTED, client.connection.value.state)
            assertEquals("again", client.execute("echo again").output)

            // 3. A missing file: the TV's error comes back as is, and the session survives.
            val ghost = RemoteEntry("ghost.bin", EntryKind.FILE, 1, 0L)
            val absent = browser.download(ready(browser.prepareDownload(trial, ghost, DiskTarget(pc), "f.bin")))
            println("Missing file: ${absent.failures}")
            assertEquals(1, absent.failures.size)
            assertFalse(absent.interrupted)
            assertFalse(File(pc, "f.bin").exists())

            // 4. The guard: never a whole storage, under any of its names.
            for ((parent, root) in listOf(
                "/storage" to "emulated",
                "/storage/emulated" to "0",
                "/storage/self" to "primary",
                "/sdcard" to "Android",
                "/" to "storage",
                "/" to "sdcard",
                "/data/local" to "tmp",
            )) {
                val review = browser.prepareDeletion(parent, RemoteEntry(root, EntryKind.FOLDER, 0, 0L))
                assertEquals("$parent/$root", DeletionReview.Protected, review)
            }
            // The delete command carries the same guard, exercised with echo in place of rm.
            val guardTrial = FolderInventory.deletionCommand("/storage/emulated/0", DeletionKind.FOLDER)
                .replace("; rm -rf ", "; echo PASSED ")
            val guard = client.execute(guardTrial)
            assertEquals("Guard: $guard", 5, guard.code)
            assertFalse(guard.output.contains("PASSED"))
            // An ordinary folder passes, with its contents.
            val ordinary = browser.prepareDeletion(downloads, folder) as DeletionReview.Ready
            println("Deleting the test folder: ${ordinary.plan}")
            assertEquals(DeletionPlan(trial, DeletionKind.FOLDER, 3, 2, ordinary.plan.size), ordinary.plan)

            // 5. A file is deleted; a symlink is deleted without its target.
            val file = browser.prepareDeletion(trial, entry(browser, trial, "a.txt")) as DeletionReview.Ready
            assertEquals(DeletionOutcome.DELETED, browser.delete(file.plan).outcome)
            assertTrue((browser.listFolder(trial) as FolderRead.Read).entries.none { it.name == "a.txt" })

            val target = "$tempDir-target"
            val link = "$tempDir-link"
            client.execute("mkdir ${quote(target)} && touch ${quote("$target/x")} && ln -s ${quote(target)} ${quote(link)}")
            val linkEntry = entry(browser, "/data/local/tmp", "$name-link")
            assertTrue("A link to a folder", linkEntry.link && linkEntry.folder)
            val linkPlan = browser.prepareDeletion("/data/local/tmp", linkEntry) as DeletionReview.Ready
            assertEquals(DeletionKind.LINK, linkPlan.plan.kind)
            assertEquals(DeletionOutcome.DELETED, browser.delete(linkPlan.plan).outcome)
            val leftover = client.execute("[ ! -L ${quote(link)} ] && [ -f ${quote("$target/x")} ] && echo intact")
            assertEquals("The link is gone, its target and contents remain", "intact", leftover.output)

            // 6. The test folder itself, through the app's code path.
            assertEquals(DeletionOutcome.DELETED, browser.delete(ordinary.plan).outcome)
            assertTrue(browser.listFolder(trial) is FolderRead.NotFound)

            // 7. What the shell cannot delete: the TV's error as is. /proc can never be deleted.
            val rejection = browser.delete(DeletionPlan("/proc/version", DeletionKind.FILE))
            println("Delete /proc/version: $rejection")
            assertEquals(DeletionOutcome.FAILED, rejection.outcome)
        } finally {
            val leftovers = listOf(trial, "$tempDir-target", "$tempDir-link", "$tempDir-names").joinToString(" ") { quote(it) }
            println("Cleanup: ${client.execute("rm -rf $leftovers")}")
            client.disconnect()
            local.deleteRecursively()
        }
    }

    private suspend fun entry(browser: FileBrowser, folder: String, name: String): RemoteEntry =
        (browser.listFolder(folder) as FolderRead.Read).entries.single { it.name == name }

    private fun ready(review: DownloadReview): DownloadPlan = (review as DownloadReview.Ready).plan

    private fun fingerprint(file: File): String =
        MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
