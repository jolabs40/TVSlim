package net.jolabs40.tvslim.windows.adb

import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.tvapp.TvApp
import net.jolabs40.tvslim.tvapp.TvAppState
import net.jolabs40.tvslim.tvapp.TvResult
import net.jolabs40.tvslim.tvapp.GithubSource
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.windows.Locations
import net.jolabs40.tvslim.windows.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Updates the TV app from GitHub on a real TV: latest `android-v*` release, certificate check, install, grant,
 * watchdog. The foreground app must stay there (an app already running is not relaunched). Installs on the TV,
 * with a temporary journal:
 *
 *     ./gradlew jvmTest --tests "*ApplicationTvMaterielTest*" '-Pmateriel=192.168.2.135' -PapplicationTv=1 --rerun
 */
class TvAppHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")
    private val allowed = System.getProperty("tvslim.tvApp") == "1"

    @Test
    fun `the TV app updates from GitHub without bringing anything to the foreground`() = runBlocking<Unit> {
        assumeTrue("-Pmateriel=<adresse> -PapplicationTv=1 : installe sur un vrai téléviseur", host != null && allowed)
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        assertTrue("Connexion à $host : ${client.connection.value}", client.connect(host!!))
        val folder = Files.createTempDirectory("tvslim-application-tv").toFile()
        try {
            val journal = JournalRepository(Files.createTempFile("journal", ".json").toFile().also { it.delete() })
            val application = TvApp(
                executor = client,
                installation = ApkInstallation(client, client, journal),
                engine = DebloatEngine(client, journal),
                reader = RemoteReader(client),
                source = GithubSource(agent = "TVSlim-Windows/essai"),
                expectedFingerprint = AppInfo.ANDROID_CERTIFICATE_FINGERPRINT,
                folder = folder,
            )
            suspend fun foreground() = client.execute("dumpsys activity activities | grep -m1 topResumedActivity").output.trim()

            val available = application.last()
            val before = application.situation(available)
            val screenBefore = foreground()
            println("Avant : ${before.installed?.versionName} installée, ${available?.version} publiée, ${before.state} ; au premier plan : $screenBefore")
            println("État : ${client.execute(TvApp.STATE_COMMAND).output.trim()}")

            val steps = mutableListOf<String>()
            val result = application.install { steps += it.javaClass.simpleName }
            val after = application.situation(available)
            val screenAfter = foreground()
            println("Résultat : $result")
            println("Étapes : ${steps.distinct()}")
            println("Après : ${after.installed?.versionName}, ${after.state} ; au premier plan : $screenAfter")
            println("Journal : ${journal.actions.value.map { "${it.type} ${it.target}" }}")

            assertTrue(result.toString(), result is TvResult.Succeeded && result.authorized && result.guardian)
            assertEquals(TvAppState.UP_TO_DATE, after.state)
            assertEquals("Rien ne doit avoir pris le premier plan", screenBefore, screenAfter)
        } finally {
            folder.deleteRecursively()
            client.disconnect()
        }
    }
}
