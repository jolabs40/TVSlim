package net.jolabs40.tvslim.windows.maj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How the app was distributed, which decides what an update may do. */
enum class ModeDistribution { INSTALLEE, PORTABLE, DEVELOPPEMENT }

data class Distribution(val mode: ModeDistribution, val executable: File?) {
    companion object {
        /**
         * The jpackage launcher sets `jpackage.app-path`; a Gradle run does not. The portable build has an
         * `app/portable` marker file added to the archive by CI.
         */
        fun detecter(): Distribution {
            val chemin = System.getProperty("jpackage.app-path")
                ?: return Distribution(ModeDistribution.DEVELOPPEMENT, null)
            val executable = File(chemin)
            val portable = File(File(executable.parentFile, "app"), "portable").exists()
            return Distribution(
                if (portable) ModeDistribution.PORTABLE else ModeDistribution.INSTALLEE,
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
class InstallateurMiseAJour(
    private val dossier: File,
    private val client: ClientGithub,
    private val clePublique: String,
) {

    class SignatureInvalide : IOException("signature invalide")

    /**
     * Downloads the installer and its signature, then verifies it. A file with a bad signature is deleted at once
     * and never executed.
     */
    suspend fun preparer(
        maj: MiseAJourDisponible,
        surProgression: (Float) -> Unit,
        surVerification: () -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dossier.mkdirs()
        // Clean up leftovers from a previous attempt.
        dossier.listFiles()
            ?.filter { it.name.endsWith(".msi") || it.name.endsWith(".part") }
            ?.forEach { it.delete() }

        val signature = client.texte(maj.signature.url)
        val msi = File(dossier, ChoixPublication.nomInstallateur(maj.version))
        client.telecharger(maj.installateur.url, msi, TAILLE_MAXIMALE, surProgression)

        surVerification()
        if (!VerificationSignature.verifier(msi, maj.version.toString(), signature, clePublique)) {
            msi.delete()
            throw SignatureInvalide()
        }
        msi
    }

    /** Starts the install outside the app's job object. The caller must exit right after. */
    fun installerPuisRelancer(msi: File, executable: File?) {
        if (lancerRelais(processusAAttendre(), msi, executable)) return
        // Fallback: Explorer opens the installer outside the job. The install then shows its normal UI and
        // TV Slim is not restarted automatically.
        Traces.avertir(TAG, "Relais WMI indisponible : installation confiée à l'Explorateur")
        runCatching { ProcessBuilder("explorer.exe", msi.absolutePath).start() }
            .onFailure { Traces.avertir(TAG, "Explorateur indisponible", it) }
    }

    companion object {
        /** A TV Slim installer is under 100 MB; anything much larger is wrong. */
        const val TAILLE_MAXIMALE = 400L * 1024 * 1024

        private const val TAG = "MiseAJour"
        private const val DELAI_LANCEMENT_S = 30L

        /**
         * Returns the app and its launcher: jpackage runs the JVM as a child of `TV Slim.exe`, and that parent
         * holds the executable the MSI must replace.
         */
        fun processusAAttendre(): List<Long> {
            val courant = ProcessHandle.current()
            val commande = courant.info().command().orElse("")
            val lanceur = courant.parent().orElse(null)?.takeIf { parent ->
                commande.isNotEmpty() && parent.info().command().orElse("").equals(commande, ignoreCase = true)
            }
            return listOfNotNull(courant.pid(), lanceur?.pid())
        }

        /** Creates the relay through WMI; returns whether Windows accepted it. */
        fun lancerRelais(pids: List<Long>, msi: File, executable: File?): Boolean = runCatching {
            val journal = File(msi.parentFile, "relais-lancement.log")
            val lancement = ProcessBuilder(commandeLancement(scriptRelais(pids, msi, executable)))
                .redirectErrorStream(true)
                .redirectOutput(journal)
                .start()
            val termine = lancement.waitFor(DELAI_LANCEMENT_S, TimeUnit.SECONDS)
            if (!termine) lancement.destroyForcibly()
            val accepte = termine && lancement.exitValue() == 0
            if (!accepte) Traces.avertir(TAG, "WMI a refusé le relais (voir ${journal.name})")
            accepte
        }.getOrElse { erreur ->
            Traces.avertir(TAG, "Relais impossible à lancer", erreur)
            false
        }

        /**
         * PowerShell relay script: waits for the app and its launcher to exit, installs with `/passive`, logs the
         * `msiexec` exit code, restarts the app. Paths are single-quoted literals. No double quotes anywhere:
         * neither Java nor the WMI command line pass them through intact, so `[char]34` produces them in
         * PowerShell.
         */
        fun scriptRelais(pids: List<Long>, msi: File, executable: File?): String {
            val journal = File(msi.parentFile, "installation.log")
            return buildString {
                append("\$ErrorActionPreference = 'SilentlyContinue'; ")
                if (pids.isNotEmpty()) append("Wait-Process -Id ${pids.joinToString(",")} -Timeout 120; ")
                append("\$msi = ${litteral(msi.absolutePath)}; ")
                append("\$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ")
                append("@('/i', ([char]34 + \$msi + [char]34), '/passive', '/norestart') -Wait -PassThru; ")
                append("Add-Content -Path ${litteral(journal.absolutePath)} ")
                append("-Value ((Get-Date -Format s) + ' msiexec ' + \$p.ExitCode); ")
                if (executable != null) append("Start-Process -FilePath ${litteral(executable.absolutePath)}")
            }
        }

        /**
         * Command the app runs: a short-lived PowerShell (it may die with the app) that asks WMI to create the
         * relay and exits with WMI's return value (0 means created). The relay script is a literal inside it, so
         * its single quotes are doubled a second time.
         */
        fun commandeLancement(scriptRelais: String): List<String> {
            val ligne = "powershell.exe -NoProfile -NonInteractive -WindowStyle Hidden -Command $scriptRelais"
            val lanceur = "\$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create " +
                "-Arguments @{ CommandLine = ${litteral(ligne)} }; exit [int]\$r.ReturnValue"
            return listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", lanceur)
        }

        private fun litteral(texte: String) = "'" + texte.replace("'", "''") + "'"
    }
}
