package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.installation.ApkReview
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.windows.Locations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Sends a real APK to a real TV, through the app's ADB client and the shared core. Opt-in because it installs:
 *
 *     ./gradlew jvmTest --tests "*InstallationHardwareTest*" '-Phardware=192.168.2.135' '-Papk=C:/…/app-tv-debug.apk' --rerun
 *
 * Pick a harmless APK: this repo's TV app, already installed and signed with the same key, is reinstalled and
 * keeps its data and its `WRITE_SECURE_SETTINGS` permission. Temporary journal; uses the app's ADB key, like
 * `CapturesHardwareTest`.
 */
class InstallationHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")
    private val path: String? = System.getProperty("tvslim.apk")

    @Test
    fun `an APK is sent, installed and logged`() = runBlocking<Unit> {
        assumeTrue("-Phardware=<address> -Papk=<file> to install on a real TV", host != null && path != null)
        val apk = File(path!!)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        val connected = client.connect(host!!)
        assertTrue("Connection to $host: ${client.connection.value}", connected)
        try {
            val journal = JournalRepository(File(Files.createTempDirectory("tvslim-installation").toFile(), "journal.json"))
            val installation = ApkInstallation(client, client, journal)

            val review = installation.examine(apk, apk.name)
            println("Review: $review")
            assertTrue(review.toString(), review is ApkReview.Ready)

            val progress = mutableListOf<Long>()
            val start = System.currentTimeMillis()
            val result = installation.install((review as ApkReview.Ready).apk) { sent, _ -> progress += sent }
            println("Result in ${System.currentTimeMillis() - start} ms, ${progress.size} progress updates: $result")

            assertTrue(result.toString(), result is InstallationResult.Succeeded)
            assertEquals(apk.length(), progress.last())
            assertEquals(ActionType.INSTALLATION, journal.actions.value.single().type)
        } finally {
            client.disconnect()
        }
    }
}
