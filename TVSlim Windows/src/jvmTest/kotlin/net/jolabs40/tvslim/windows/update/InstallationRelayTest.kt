package net.jolabs40.tvslim.windows.update

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the update relay for real: spawned by WMI, it waits for the given processes to exit, installs an MSI over
 * the current version, then relaunches the app. Opt-in, on a machine where an older version is installed and
 * running:
 *
 *     ./gradlew jvmTest --tests "*RelaisInstallationTest*" --rerun \
 *         -PrelaisMsi=C:\…\TVSlim-Windows-0.9.1.msi \
 *         -PrelaisExe="C:\Users\…\AppData\Local\TV Slim\TV Slim.exe" \
 *         -PrelaisPid=<app pid>,<launcher pid>
 *
 * The test returns as soon as WMI has created the relay. Closing the app then lets the install proceed; the
 * result is in `installation.log`.
 */
class InstallationRelayTest {

    @Test
    fun `WMI creates the relay that will install the next version`() {
        val msi = System.getProperty("tvslim.relay.msi")
        assumeTrue("-PrelaisMsi=<installateur> pour éprouver le relais", msi != null)
        val executable = System.getProperty("tvslim.relay.exe")?.let(::File)
        val pids = System.getProperty("tvslim.relay.pid").orEmpty()
            .split(',').mapNotNull { it.trim().toLongOrNull() }

        val accepted = UpdateInstaller.launchRelay(pids, File(msi!!), executable)

        File(System.getProperty("tvslim.captures"), "relais.txt").apply {
            parentFile.mkdirs()
            writeText("pids attendus=$pids\nrelais accepté par WMI=$accepted\n")
        }
        assertTrue("WMI n'a pas créé le relais", accepted)
    }
}
