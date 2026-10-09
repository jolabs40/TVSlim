package net.jolabs40.tvslim.installation

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ApkInstaller
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** APK installation: what is read from the TV before confirming, what never reaches its shell, and what is logged. */
class ApkInstallationTest {

    /** Fake TV that records commands and uploads and returns canned replies. */
    private class FakeTv(
        private val reading: (String) -> ShellResult = { ShellResult(1, "34") },
        private val installResponse: ShellResult = ShellResult(0, "Success"),
    ) : CommandExecutor, ApkInstaller {
        val commands = mutableListOf<String>()
        val uploads = mutableListOf<File>()

        override suspend fun execute(command: String): ShellResult {
            commands += command
            return reading(command)
        }

        override suspend fun install(apk: File, onSent: (sent: Long, total: Long) -> Unit): ShellResult {
            uploads += apk
            onSent(apk.length() / 2, apk.length())
            onSent(apk.length(), apk.length())
            return installResponse
        }
    }

    private val file = File.createTempFile("tvslim", ".apk").apply {
        deleteOnExit()
        writeBytes(ByteArray(2048))
    }

    private val manifest = ApkManifest("net.jolabs40.hippietv", versionCode = 240, versionName = "2.4.0", minSdk = 26)

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun apk(installed: InstalledVersion? = null) =
        ChosenApk(file, "HippieTV.apk", file.length(), manifest, installed)

    @Test
    fun `an app missing from the tv is a new install, and nothing is uploaded`() = runTest {
        val tv = FakeTv(reading = { ShellResult(1, "34\n") })

        val review = ApkInstallation(tv, tv, journal()).examine(file, "HippieTV.apk", manifest)

        val chosen = (review as ApkReview.Ready).apk
        assertNull(chosen.installed)
        assertEquals(InstallationKind.NEW, chosen.kind)
        assertEquals(2048L, chosen.size)
        assertEquals(1, tv.commands.size)
        assertTrue("Examining sends nothing", tv.uploads.isEmpty())
    }

    @Test
    fun `the installed version is read before the factory version hidden behind it`() = runTest {
        val output = listOf(
            "34",
            "    versionCode=251 minSdk=26 targetSdk=35",
            "    versionName=2.5.1 beta",
            "    versionCode=12 minSdk=21 targetSdk=28",
            "    versionName=1.0",
        ).joinToString("\n")
        val tv = FakeTv(reading = { ShellResult(0, output) })

        val chosen = (ApkInstallation(tv, tv, journal()).examine(file, "HippieTV.apk", manifest) as ApkReview.Ready).apk

        assertEquals(InstalledVersion(251, "2.5.1 beta"), chosen.installed)
        assertEquals(InstallationKind.DOWNGRADE, chosen.kind)
    }

    @Test
    fun `update and reinstall are told apart by the version code`() {
        assertEquals(InstallationKind.UPDATE, apk(InstalledVersion(239, "2.3.9")).kind)
        assertEquals(InstallationKind.REINSTALLATION, apk(InstalledVersion(240, "2.4.0")).kind)
    }

    @Test
    fun `a too old Android is refused before any upload`() = runTest {
        val tv = FakeTv(reading = { ShellResult(1, "25") })

        val review = ApkInstallation(tv, tv, journal()).examine(file, "HippieTV.apk", manifest)

        assertEquals(ApkReview.Rejected(ApkRejection.ANDROID_TOO_OLD, minSdk = 26, tvSdk = 25), review)
        assertTrue(tv.uploads.isEmpty())
    }

    @Test
    fun `a forged package name never reaches the shell`() = runTest {
        val tv = FakeTv()

        val review = ApkInstallation(tv, tv, journal())
            .examine(file, "trap.apk", manifest.copy(packageName = "x; reboot"))

        assertEquals(ApkReview.Rejected(ApkRejection.INVALID_PACKAGE), review)
        assertTrue(tv.commands.isEmpty())
    }

    @Test
    fun `an unreachable tv is reported as such`() = runTest {
        val tv = FakeTv(reading = { ShellResult.unavailable("No TV connected.") })

        val review = ApkInstallation(tv, tv, journal()).examine(file, "HippieTV.apk", manifest)

        assertEquals(ApkReview.Rejected(ApkRejection.TV_UNREACHABLE), review)
    }

    @Test
    fun `a file that is not an APK is refused without querying the tv`() = runTest {
        val tv = FakeTv()

        val review = ApkInstallation(tv, tv, journal()).examine(file, "HippieTV.apk")

        assertEquals(ApkReview.Rejected(ApkRejection.NOT_AN_APK), review)
        assertTrue(tv.commands.isEmpty())
    }

    @Test
    fun `a successful install is logged, with no undo command and no uninstall`() = runTest {
        val tv = FakeTv()
        val logbook = journal()
        val progress = mutableListOf<Long>()

        val result = ApkInstallation(tv, tv, logbook).install(apk()) { sent, _ -> progress += sent }

        assertEquals(InstallationResult.Succeeded(apk()), result)
        assertEquals(listOf(1024L, 2048L), progress)
        val action = logbook.actions.value.single()
        assertEquals(ActionType.INSTALLATION, action.type)
        assertEquals("net.jolabs40.hippietv", action.target)
        assertEquals("Installation de HippieTV.apk (2.4.0)", action.label)
        assertEquals("", action.undoCommand)
        assertTrue(action.succeeded)
        assertFalse("Never uninstall", tv.commands.any { it.contains("uninstall") })
    }

    @Test
    fun `an Android refusal is logged as a failure, with its cause and raw output`() = runTest {
        val rejection = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package net.jolabs40.hippietv signatures do not " +
            "match newer version; ignoring!]"
        val tv = FakeTv(installResponse = ShellResult(1, rejection))
        val logbook = journal()

        val result = ApkInstallation(tv, tv, logbook).install(apk())

        val failure = result as InstallationResult.Failed
        assertEquals(FailureCause.SIGNATURE_MISMATCH, failure.cause)
        assertEquals(rejection, failure.detail)
        with(logbook.actions.value.single()) {
            assertFalse(succeeded)
            assertEquals(rejection, message)
        }
    }

    @Test
    fun `common Android refusals are recognized`() {
        mapOf(
            "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]" to FailureCause.DOWNGRADE,
            "Failure [INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #34 (current version is #30)]" to
                FailureCause.ANDROID_TOO_OLD,
            "Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]" to
                FailureCause.ARCHITECTURE,
            "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]" to FailureCause.INSUFFICIENT_STORAGE,
            "Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: No signature found in package]" to FailureCause.UNSIGNED,
            "Failure [INSTALL_FAILED_MISSING_SPLIT: Missing split for net.jolabs40.hippietv]" to FailureCause.INCOMPLETE,
            "Failure [INSTALL_FAILED_VERIFICATION_FAILURE]" to FailureCause.REJECTED,
            "Failure [INSTALL_PARSE_FAILED_NOT_APK: Failed to parse base.apk]" to FailureCause.INVALID,
            "Failure [INSTALL_FAILED_INTERNAL_ERROR: Session relinquished]" to FailureCause.OTHER,
            "Error: java.lang.SecurityException" to FailureCause.OTHER,
        ).forEach { (output, cause) ->
            assertEquals(output, cause, ApkInstallation.failureCause(ShellResult(1, output)))
        }
        assertEquals(FailureCause.CONNECTION, ApkInstallation.failureCause(ShellResult.unavailable("timed out")))
    }
}
