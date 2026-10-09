package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.files.RemotePath
import net.jolabs40.tvslim.files.UploadReview
import net.jolabs40.tvslim.files.CreationOutcome
import net.jolabs40.tvslim.files.FolderRead
import net.jolabs40.tvslim.files.ShortcutKind
import net.jolabs40.tvslim.files.FileBrowser
import net.jolabs40.tvslim.files.quote
import net.jolabs40.tvslim.windows.Locations
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
 * Browse and upload on a real TV, through the app's ADB client and the shared core. Opt-in because it writes,
 * in a `tvslim-trial-...` folder under Download that is deleted at the end:
 *
 *     ./gradlew jvmTest --tests "*DepotMaterielTest*" '-Pmateriel=192.168.2.135' -Pdepot=1 --rerun
 */
class UploadHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")

    @Test
    fun `a folder uploads whole and intact, and the other outcomes are reported`() = runBlocking<Unit> {
        assumeTrue(
            "-Pmateriel=<adresse> -Pdepot=1 pour écrire sur un vrai téléviseur",
            host != null && System.getProperty("tvslim.upload") != null,
        )
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connexion à $host : ${client.connection.value}", client.connect(host!!))
        val browser = FileBrowser(client, client)
        val name = "tvslim-essai-${System.currentTimeMillis()}"
        val destination = "/sdcard/Download"
        val trial = RemotePath.join(destination, name)

        // Locally: a text file, a name with an apostrophe and spaces, 5 MB of random data in a subfolder, an empty folder.
        val local = File(Files.createTempDirectory("tvslim-depot").toFile(), name).apply { mkdirs() }
        File(local, "a.txt").writeText("bonjour")
        File(local, "l'été 2024.txt").writeText("apostrophe")
        val big = File(local, "sous/b.bin").apply { parentFile.mkdirs(); writeBytes(Random(7).nextBytes(5_000_000)) }
        File(local, "vide").mkdirs()

        try {
            val shortcuts = browser.shortcuts()
            println("Raccourcis : ${shortcuts.map { it.path }}")
            assertEquals(ShortcutKind.INTERNAL, shortcuts.first().kind)
            assertTrue(browser.listFolder("/data") is FolderRead.Rejected)
            assertTrue(browser.listFolder("/nexiste/pas") is FolderRead.NotFound)
            val root = browser.listFolder("/") as FolderRead.Read
            assertTrue("sdcard est un lien vers un dossier", root.entries.single { it.name == "sdcard" }.let { it.link && it.folder })

            val review = browser.examine(batchFrom(listOf(local)), destination)
            val plan = (review as UploadReview.Ready).plan
            assertTrue(plan.existing.isEmpty())

            var marks = 0
            val start = System.currentTimeMillis()
            val result = browser.upload(plan) { marks++ }
            val duration = System.currentTimeMillis() - start
            println("Envoi en $duration ms, $marks signes : $result")
            assertTrue(result.toString(), result.complete)

            val justRead = browser.listFolder(trial) as FolderRead.Read
            assertEquals(listOf("sous", "vide", "a.txt", "l'été 2024.txt"), justRead.entries.map { it.name })
            val remote = browser.listFolder("$trial/sous") as FolderRead.Read
            assertEquals(5_000_000L, remote.entries.single().size)
            val md5 = client.execute("md5sum ${quote("$trial/sous/b.bin")}").output.substringBefore(' ')
            assertEquals(fingerprint(big), md5)

            // A second time: the folder already exists, which is reported without changing the upload.
            assertEquals(listOf(name), (browser.examine(batchFrom(listOf(local)), destination) as UploadReview.Ready).plan.existing)

            // Cancel halfway through a large file: the upload stops, the session still works.
            val huge = File(local.parentFile, "enorme.bin").apply { writeBytes(Random(3).nextBytes(60_000_000)) }
            var cancelled = false
            val plan2 = (browser.examine(batchFrom(listOf(huge)), trial) as UploadReview.Ready).plan
            val stopped = browser.upload(plan2, cancelled = { cancelled }) { if (it.sent > 5_000_000) cancelled = true }
            println("Annulé : $stopped")
            assertTrue(stopped.cancelled)
            val after = browser.listFolder(trial) as FolderRead.Read
            println("Après l'annulation : ${after.entries.map { "${it.name} ${it.size}" }}")

            // Where the shell cannot write, the TV's error comes back as is, file by file.
            val rejected = browser.upload(plan.copy(destination = "/system", batch = batchFrom(listOf(File(local, "a.txt")))))
            println("Vers /system : $rejected")
            assertEquals(0, rejected.sentCount)
            assertFalse(rejected.interrupted)
            assertTrue(rejected.failures.isNotEmpty())

            assertEquals(CreationOutcome.CREATED, browser.createFolder(trial, "Nouveau dossier").outcome)
            assertEquals(CreationOutcome.EXISTS, browser.createFolder(trial, "Nouveau dossier").outcome)
        } finally {
            println("Nettoyage : ${client.execute("rm -r ${quote(trial)}")}")
            client.disconnect()
            local.parentFile.deleteRecursively()
        }
    }

    private fun fingerprint(file: File): String =
        MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
