package net.jolabs40.tvslim.windows.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.windows.tools.AppLog
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How the app was distributed, which decides what an update may do. */
enum class DistributionMode { INSTALLED, PORTABLE, DEVELOPMENT }

data class Distribution(val mode: DistributionMode, val executable: File?) {
    companion object {
        /**
         * The jpackage launcher sets `jpackage.app-path`; a Gradle run does not. The portable build has an
         * `app/portable` marker file added to the archive by CI.
         */
        fun detect(): Distribution {
            val path = System.getProperty("jpackage.app-path")
                ?: return Distribution(DistributionMode.DEVELOPMENT, null)
            val executable = File(path)
            val portable = File(File(executable.parentFile, "app"), "portable").exists()
            return Distribution(
                if (portable) DistributionMode.PORTABLE else DistributionMode.INSTALLED,
                executable,
            )
        }
    }
}

/**
 * Downloads, verifies and installs a new version.
 *
 * The installer is a per-user MSI: it replaces the old version without admin rights or UAC, but cannot replace
 * files in use. A relay process waits for the app to exit, runs the install, then restarts TV Slim.
 *
 * The relay cannot be a plain child process. The jpackage launcher runs the app in a Windows job object with
 * `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, so everything it starts dies with it. Tested on the installed app and in
 * a replica job: a child dies, a process created through WMI survives in the user's session. The relay is
 * therefore created through WMI; as a fallback, Explorer opens the installer, also outside the job.
 */
class UpdateInstaller(
    private val folder: File,
    private val client: GithubClient,
    private val publicKeyBase64: String,
) {

    class InvalidSignature : IOException("signature invalide")

    /**
     * Downloads the installer and its signature, then verifies it. A file with a bad signature is deleted at once
     * and never executed.
     */
    suspend fun prepare(
        update: AvailableUpdate,
        onProgress: (Float) -> Unit,
        onVerification: () -> Unit,
    ): File = withContext(Dispatchers.IO) {
        folder.mkdirs()
        // Clean up leftovers from a previous attempt.
        folder.listFiles()
            ?.filter { it.name.endsWith(".msi") || it.name.endsWith(".part") }
            ?.forEach { it.delete() }

        val signature = client.text(update.signature.url)
        val msi = File(folder, ReleaseChoice.installerName(update.version))
        client.download(update.installer.url, msi, MAXIMUM_SIZE, onProgress)

        onVerification()
        if (!SignatureVerification.check(msi, update.version.toString(), signature, publicKeyBase64)) {
            msi.delete()
            throw InvalidSignature()
        }
        msi
    }

    /** Starts the install outside the app's job object. The caller must exit right after. */
    fun installThenRelaunch(msi: File, executable: File?) {
        if (launchRelay(processesToWaitFor(), msi, executable)) return
        // Fallback: Explorer opens the installer outside the job. The install then shows its normal UI and
        // TV Slim is not restarted automatically.
        AppLog.warn(TAG, "Relais WMI indisponible : installation confiée à l'Explorateur")
        runCatching { ProcessBuilder("explorer.exe", msi.absolutePath).start() }
            .onFailure { AppLog.warn(TAG, "Explorateur indisponible", it) }
    }

    companion object {
        /** A TV Slim installer is under 100 MB; anything much larger is wrong. */
        const val MAXIMUM_SIZE = 400L * 1024 * 1024

        private const val TAG = "MiseAJour"
        private const val LAUNCH_TIMEOUT_S = 30L

        /**
         * Returns the app and its launcher: jpackage runs the JVM as a child of `TV Slim.exe`, and that parent
         * holds the executable the MSI must replace.
         */
        fun processesToWaitFor(): List<Long> {
            val current = ProcessHandle.current()
            val command = current.info().command().orElse("")
            val launcher = current.parent().orElse(null)?.takeIf { parent ->
                command.isNotEmpty() && parent.info().command().orElse("").equals(command, ignoreCase = true)
            }
            return listOfNotNull(current.pid(), launcher?.pid())
        }

        /** Creates the relay through WMI; returns whether Windows accepted it. */
        fun launchRelay(pids: List<Long>, msi: File, executable: File?): Boolean = runCatching {
            val journal = File(msi.parentFile, "relais-lancement.log")
            val launch = ProcessBuilder(launchCommand(relayScript(pids, msi, executable)))
                .redirectErrorStream(true)
                .redirectOutput(journal)
                .start()
            val finished = launch.waitFor(LAUNCH_TIMEOUT_S, TimeUnit.SECONDS)
            if (!finished) launch.destroyForcibly()
            val accepted = finished && launch.exitValue() == 0
            if (!accepted) AppLog.warn(TAG, "WMI a refusé le relais (voir ${journal.name})")
            accepted
        }.getOrElse { error ->
            AppLog.warn(TAG, "Relais impossible à lancer", error)
            false
        }

        /**
         * PowerShell relay script: waits for the app and its launcher to exit, installs with `/passive`, logs the
         * `msiexec` exit code, restarts the app. Paths are single-quoted literals. No double quotes anywhere:
         * neither Java nor the WMI command line pass them through intact, so `[char]34` produces them in
         * PowerShell.
         */
        fun relayScript(pids: List<Long>, msi: File, executable: File?): String {
            val journal = File(msi.parentFile, "installation.log")
            return buildString {
                append("\$ErrorActionPreference = 'SilentlyContinue'; ")
                if (pids.isNotEmpty()) append("Wait-Process -Id ${pids.joinToString(",")} -Timeout 120; ")
                append("\$msi = ${literal(msi.absolutePath)}; ")
                append("\$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ")
                append("@('/i', ([char]34 + \$msi + [char]34), '/passive', '/norestart') -Wait -PassThru; ")
                append("Add-Content -Path ${literal(journal.absolutePath)} ")
                append("-Value ((Get-Date -Format s) + ' msiexec ' + \$p.ExitCode); ")
                if (executable != null) append("Start-Process -FilePath ${literal(executable.absolutePath)}")
            }
        }

        /**
         * Command the app runs: a short-lived PowerShell (it may die with the app) that asks WMI to create the
         * relay and exits with WMI's return value (0 means created). The relay script is a literal inside it, so
         * its single quotes are doubled a second time.
         */
        fun launchCommand(relayScript: String): List<String> {
            val line = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command $relayScript"
            val launcher = "\$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create " +
                "-Arguments @{ CommandLine = ${literal(line)} }; exit [int]\$r.ReturnValue"
            return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", launcher)
        }

        private fun literal(text: String) = "'" + text.replace("'", "''") + "'"
    }
}
