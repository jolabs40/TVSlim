package net.jolabs40.tvslim.tvapp

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.installation.ApkSignatureTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ApkInstaller
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Installing the TV app from GitHub. Nothing reaches the TV unless it is signed with our key, then the order is
 * install, grant, launch, start the boot guard.
 */
class TvAppTest {

    private val fixtures = File("src/test/fixtures/apk")

    /** Stateful fake TV: installing adds the app, and permissions granted by `pm grant` show up in `dumpsys`. */
    private class FakeTv(
        var installed: Boolean = false,
        /** Opened once already: Android no longer treats it as stopped, and an update does not change that. */
        var launched: Boolean = false,
        private val sdk: Int = 34,
        private val guardianResponse: String = "Broadcasting: Intent { … }\nBroadcast completed: result=1",
    ) : CommandExecutor, ApkInstaller {
        val commands = mutableListOf<String>()
        val uploads = mutableListOf<File>()
        private val granted = mutableSetOf<String>()

        override suspend fun execute(command: String): ShellResult {
            commands += command
            return when {
                command.startsWith("getprop ro.build.version.sdk;") ->
                    if (installed) ShellResult(0, "$sdk\n    versionCode=10000 minSdk=26\n    versionName=1.0.0") else ShellResult(1, "$sdk\n")
                command == "getprop ro.build.version.sdk" -> ShellResult(0, "$sdk\n")
                command == TvApp.STATE_COMMAND ->
                    if (installed) ShellResult(0, "    User 0: ceDataInode=1 installed=true stopped=${!launched} notLaunched=${!launched} enabled=0\n    User 0:") else ShellResult(1, "")
                command.startsWith("dumpsys package ${TvApp.PACKAGE_NAME} | grep") ->
                    if (installed) ShellResult(0, "    versionCode=10000 minSdk=26\n    versionName=1.0.0") else ShellResult(1, "")
                command == "dumpsys package ${TvApp.PACKAGE_NAME}" -> ShellResult(0, dumpsys())
                command.startsWith("pm grant ") -> {
                    granted += command.substringAfterLast(' ')
                    ShellResult(0, "")
                }
                command.startsWith("am broadcast") -> ShellResult(0, guardianResponse)
                command.startsWith("am start") -> {
                    launched = true
                    ShellResult(0, "")
                }
                else -> ShellResult(0, "")
            }
        }

        override suspend fun install(apk: File, onSent: (sent: Long, total: Long) -> Unit): ShellResult {
            uploads += apk
            onSent(apk.length(), apk.length())
            installed = true
            return ShellResult(0, "Success")
        }

        private fun dumpsys() = if (!installed) "Unable to find package: ${TvApp.PACKAGE_NAME}" else buildString {
            appendLine("    requested permissions:")
            appendLine("      ${TvApp.WRITE_SECURE_SETTINGS}")
            appendLine("      ${TvApp.POST_NOTIFICATIONS}")
            appendLine("    install permissions:")
            appendLine("      ${TvApp.WRITE_SECURE_SETTINGS}: granted=${TvApp.WRITE_SECURE_SETTINGS in granted}")
            appendLine("    runtime permissions:")
            appendLine("      ${TvApp.POST_NOTIFICATIONS}: granted=${TvApp.POST_NOTIFICATIONS in granted}")
        }
    }

    /** Fake GitHub: one release, serving the given test APK. */
    private inner class Github(
        private val apk: String = "tv-cle-a.apk",
        private val failing: Boolean = false,
    ) : ReleaseSource {
        var downloads = 0

        override suspend fun releases(): String {
            if (failing) throw IOException("hors ligne")
            return """[{"tag_name":"android-v1.1.0","assets":[{"name":"TVSlim-TV-1.1.0.apk",""" +
                """"browser_download_url":"https://github.com/jolabs40/TVSlim/releases/download/android-v1.1.0/TVSlim-TV-1.1.0.apk","size":12375}]}]"""
        }

        override suspend fun download(url: String, target: File, maxSize: Long, progress: (Long, Long) -> Unit) {
            downloads++
            File(fixtures, apk).copyTo(target, overwrite = true)
            progress(target.length(), target.length())
        }
    }

    private val folder = File.createTempFile("tvslim", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
    private val journal = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun application(tv: FakeTv, github: ReleaseSource = Github()) = TvApp(
        executor = tv,
        installation = ApkInstallation(tv, tv, journal),
        engine = DebloatEngine(tv, journal),
        reader = RemoteReader(tv),
        source = github,
        expectedFingerprint = ApkSignatureTest.KEY_A,
        folder = folder,
    )

    @Test
    fun `installs, grants, launches and starts the guard, in that order`() = runTest {
        val tv = FakeTv()
        val steps = mutableListOf<TvStep>()

        val result = application(tv).install { steps += it }

        assertEquals(TvResult.Succeeded("1.1.0", authorized = true, guardian = true), result)
        assertEquals(1, tv.uploads.size)
        val order = listOf("pm grant ${TvApp.PACKAGE_NAME} ${TvApp.WRITE_SECURE_SETTINGS}", "am start", "am broadcast")
            .map { start -> tv.commands.indexOfFirst { it.startsWith(start) } }
        assertTrue("Ordre : accorder, lancer, gardien — $order", order.all { it >= 0 } && order == order.sorted())
        assertTrue("Android 13 et plus : les notifications aussi", tv.commands.any { it.endsWith(TvApp.POST_NOTIFICATIONS) })
        assertTrue(steps.first() is TvStep.Checking)
        assertTrue(steps.any { it is TvStep.Verification } && steps.any { it is TvStep.Upload })
        assertFalse("L'APK téléchargé ne reste pas sur le disque", File(folder, "tvslim-tv.apk").exists())

        val types = journal.actions.value.map { it.type }
        assertEquals(listOf(ActionType.INSTALLATION, ActionType.PERMISSION, ActionType.PERMISSION), types)
    }

    @Test
    fun `an APK signed with another key is never sent to the TV`() = runTest {
        val tv = FakeTv()

        val result = application(tv, Github(apk = "tv-cle-b.apk")).install()

        assertEquals(TvReason.CERTIFICATE, (result as TvResult.Failed).reason)
        assertEquals(ApkSignatureTest.KEY_B, result.detail)
        assertTrue(tv.uploads.isEmpty())
        assertTrue("Aucune commande n'a modifié le téléviseur", tv.commands.none { it.startsWith("pm ") || it.startsWith("am ") })
        assertFalse(File(folder, "tvslim-tv.apk").exists())
    }

    @Test
    fun `an unsigned APK is rejected the same way`() = runTest {
        val tv = FakeTv()
        val result = application(tv, Github(apk = "tv-non-signe.apk")).install()
        assertEquals(TvReason.CERTIFICATE, (result as TvResult.Failed).reason)
        assertTrue(tv.uploads.isEmpty())
    }

    @Test
    fun `without GitHub, nothing happens and the reason says so`() = runTest {
        val tv = FakeTv()
        val github = Github(failing = true)
        val result = application(tv, github).install()
        assertEquals(TvResult.Failed(TvReason.NETWORK), result)
        assertEquals(0, github.downloads)
        assertTrue(tv.commands.isEmpty())
    }

    @Test
    fun `a TV app that does not know the command does not confirm its guard`() = runTest {
        val tv = FakeTv(guardianResponse = "Broadcast completed: result=0")
        val result = application(tv).install()
        assertEquals(TvResult.Succeeded("1.1.0", authorized = true, guardian = false), result)
    }

    @Test
    fun `before Android 13, no notification permission is granted`() = runTest {
        val tv = FakeTv(sdk = 30)
        application(tv).install()
        assertTrue(tv.commands.none { it.endsWith(TvApp.POST_NOTIFICATIONS) })
    }

    @Test
    fun `the status compares what the TV has with what GitHub offers`() = runTest {
        val available = application(FakeTv()).last()
        assertEquals("1.1.0", available?.version)
        assertEquals(null, application(FakeTv(), Github(failing = true)).last())

        assertEquals(TvAppState.MISSING, application(FakeTv()).situation(available).state)

        val old = application(FakeTv(installed = true)).situation(available)
        assertEquals(10000L, old.installed?.versionCode)
        assertEquals(TvAppState.UPDATE, old.state)

        val withoutGithub = application(FakeTv(installed = true)).situation(available = null)
        assertEquals("Sans GitHub, l'autorisation manquante se dit encore", TvAppState.NO_PERMISSION, withoutGithub.state)
    }

    @Test
    fun `granting an app already installed downloads nothing`() = runTest {
        val tv = FakeTv(installed = true)
        val github = Github()

        val result = application(tv, github).authorize("1.0.0")

        assertEquals(TvResult.Succeeded("1.0.0", authorized = true, guardian = true), result)
        assertEquals(0, github.downloads)
        assertTrue(tv.uploads.isEmpty())
        assertEquals(TvAppState.UP_TO_DATE, application(tv).situation(available = null).state)
    }

    @Test
    fun `an update does not open an app already launched, so the TV keeps its program`() = runTest {
        val tv = FakeTv(installed = true, launched = true)

        val result = application(tv).install()

        assertEquals(TvResult.Succeeded("1.1.0", authorized = true, guardian = true), result)
        assertEquals(1, tv.uploads.size)
        assertTrue("Rien ne passe au premier plan : ${tv.commands}", tv.commands.none { it.startsWith("am start") })
        assertTrue(tv.commands.any { it.startsWith("am broadcast") })
    }

    @Test
    fun `granting an app already launched does not open it either`() = runTest {
        val tv = FakeTv(installed = true, launched = true)
        application(tv).authorize("1.0.0")
        assertTrue(tv.commands.none { it.startsWith("am start") })
    }

    @Test
    fun `only the main profile decides whether the app is stopped`() {
        // Captured on the TCL: the second profile, never opened, reports the app as stopped.
        val tcl = """
            |    User 0: ceDataInode=1332869 installed=true hidden=false suspended=false distractionFlags=0 stopped=false notLaunched=false enabled=0 instant=false virtual=false
            |    User 10: ceDataInode=0 installed=true hidden=false suspended=false distractionFlags=0 stopped=true notLaunched=true enabled=0 instant=false virtual=false
            |    User 0:
        """.trimMargin()
        assertFalse(TvApp.isStopped(tcl))
        assertTrue(TvApp.isStopped("    User 0: ceDataInode=1 installed=true stopped=true notLaunched=true enabled=0"))
        assertTrue("Jamais ouverte, même non arrêtée de force", TvApp.isStopped("    User 0: installed=true stopped=false notLaunched=true"))
        assertTrue("Illisible : on la lance, comme avant", TvApp.isStopped(""))
    }
}
