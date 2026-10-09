package net.jolabs40.tvslim.windows.screen

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.screen.RecordingStop
import net.jolabs40.tvslim.screen.ScreenCapture
import net.jolabs40.tvslim.screen.RecordingStart
import net.jolabs40.tvslim.screen.TvRecording
import net.jolabs40.tvslim.screen.CaptureResult
import net.jolabs40.tvslim.windows.Locations
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.AdbKeyStore
import net.jolabs40.tvslim.windows.update.GithubClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Screen of a real TV. Screenshot and video use the app's key, as in TV Slim: the video records for six seconds on
 * the TV, is copied, then deleted, leaving nothing behind. The scrcpy test downloads the pinned release from GitHub,
 * opens the mirror, then closes its window the way a user would.
 *
 *     ./gradlew jvmTest --tests "*EcranMaterielTest*" '-Pmateriel=192.168.2.135' --rerun
 *     ./gradlew jvmTest --tests "*EcranMaterielTest*" '-Pmateriel=192.168.2.135' -Pscrcpy=1 --rerun
 *
 * The second one opens a scrcpy window on the desktop during the test.
 */
class ScreenHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")
    private val output = File(System.getProperty("tvslim.captures") ?: "build/captures")

    @Test
    fun `the screenshot returns a PNG of the TV screen`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", host != null)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connexion à $host : ${client.connection.value}", client.connect(host!!))
        try {
            val start = System.currentTimeMillis()
            val result = ScreenCapture(client).takeCapture()
            println("Capture en ${System.currentTimeMillis() - start} ms")
            assertTrue(result.toString(), result is CaptureResult.Succeeded)
            result as CaptureResult.Succeeded
            println("${result.width} × ${result.height}, ${result.png.size} octets")
            output.mkdirs()
            File(output, "ecran-materiel.png").writeBytes(result.png)

            // The session still works after a binary read.
            assertEquals(0, client.execute("echo apres").code)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `the video records on the TV, is copied, then deleted`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> pour essayer sur un vrai téléviseur", host != null)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connexion à $host : ${client.connection.value}", client.connect(host!!))
        try {
            val recording = TvRecording(client, client)
            val startResult = recording.start()
            println("Démarrage : $startResult")
            assertTrue(startResult.toString(), startResult is RecordingStart.Started)

            // The TV Slim session stays free while recording: the recorder is detached.
            Thread.sleep(3_000)
            assertEquals(0, client.execute("echo pendant").code)
            assertEquals(true, recording.isAlive())
            Thread.sleep(3_000)

            val stopResult = recording.stop()
            println("Arrêt : $stopResult")
            assertTrue(stopResult.toString(), stopResult is RecordingStop.Done)
            val size = (stopResult as RecordingStop.Done).size

            output.mkdirs()
            val video = File(output, "ecran-materiel.mp4").apply { delete() }
            val start = System.currentTimeMillis()
            val copied = video.outputStream().use { recording.download(it, size) }
            println("Copie de $size octets en ${System.currentTimeMillis() - start} ms : $copied")
            assertTrue(copied.toString(), copied.succeeded)
            assertEquals(size, video.length())
            // SIGINT lets the recorder write the "moov" index, so the video plays to the end.
            assertTrue("vidéo sans index", String(video.readBytes(), Charsets.ISO_8859_1).contains("moov"))

            recording.clean()
            assertEquals("", client.execute("ls ${TvRecording.VIDEO} ${TvRecording.PID} 2>/dev/null").output)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `scrcpy downloads, opens the mirror, and closes cleanly`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> -Pscrcpy=1", host != null && System.getProperty("tvslim.scrcpy") != null)
        val folder = Files.createTempDirectory("tvslim-scrcpy").toFile()
        try {
            // The actual release file; its checksum is verified on the way.
            val exe = ScrcpyInstallation(GithubClient("Genymobile/scrcpy", "essai"), folder).install { }
            // The downloaded copy wins over any other, and its version is recent enough.
            assertEquals(exe.canonicalFile, ScrcpyLocator(folder).find()?.canonicalFile)

            val session = ScrcpySession.start(exe, ScrcpyArguments.mirror(host!!, 5555, "TV Slim - essai"))
            Thread.sleep(6_000)
            assertTrue("scrcpy s'est arrêté seul : ${session.output}", session.alive)
            session.stop()
            val code = session.waitFor()
            println("scrcpy : code $code\n" + session.output.joinToString("\n"))
            assertEquals(session.output.joinToString("\n"), 0, code)
        } finally {
            folder.deleteRecursively()
        }
    }
}
