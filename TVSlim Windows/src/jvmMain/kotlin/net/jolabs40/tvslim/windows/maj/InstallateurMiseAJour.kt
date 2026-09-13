package net.jolabs40.tvslim.windows.maj

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Comment l'application a été obtenue : ce qui décide de ce qu'une mise à jour peut faire. */
enum class ModeDistribution { INSTALLEE, PORTABLE, DEVELOPPEMENT }

data class Distribution(val mode: ModeDistribution, val executable: File?) {
    companion object {
        /**
         * Le lanceur jpackage renseigne `jpackage.app-path` ; lancée par Gradle, l'application n'en a
         * pas. La version portable porte un fichier `app/portable`, ajouté à l'archive par la CI.
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
 * Télécharge, vérifie et installe une nouvelle version.
 *
 * L'installateur est un MSI « par utilisateur » : il remplace l'ancienne version sans droits
 * d'administrateur ni fenêtre UAC — mais pas des fichiers en cours d'utilisation. Un relais attend
 * donc la fermeture de l'application, lance l'installation, puis rouvre TV Slim.
 *
 * ⚠️ Ce relais ne peut pas être un simple processus enfant. Le lanceur jpackage fait tourner
 * l'application dans un *job* Windows à fermeture fatale (`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`) :
 * tout ce qu'elle démarre meurt avec elle, relais compris. Éprouvé le 2026-09-13 sur l'application
 * installée, puis dans un job reproduit à l'identique : un enfant meurt, un processus créé par WMI
 * survit, dans la session de la personne. Le relais naît donc par WMI ; à défaut, l'Explorateur
 * ouvre l'installateur, lui aussi hors du job.
 */
class InstallateurMiseAJour(
    private val dossier: File,
    private val client: ClientGithub,
    private val clePublique: String,
) {

    class SignatureInvalide : IOException("signature invalide")

    /**
     * Télécharge l'installateur et sa signature, puis vérifie. Un fichier dont la signature ne
     * correspond pas est supprimé aussitôt : il n'est jamais exécuté.
     */
    suspend fun preparer(
        maj: MiseAJourDisponible,
        surProgression: (Float) -> Unit,
        surVerification: () -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dossier.mkdirs()
        // Les restes d'une tentative précédente ne servent plus à rien.
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

    /** Lance l'installation hors du job de l'application. L'appelant doit quitter juste après. */
    fun installerPuisRelancer(msi: File, executable: File?) {
        if (lancerRelais(processusAAttendre(), msi, executable)) return
        // Repli : l'Explorateur ouvre l'installateur lui-même, hors du job. L'installation passe
        // alors par son interface habituelle, et TV Slim se rouvre à la main.
        Traces.avertir(TAG, "Relais WMI indisponible : installation confiée à l'Explorateur")
        runCatching { ProcessBuilder("explorer.exe", msi.absolutePath).start() }
            .onFailure { Traces.avertir(TAG, "Explorateur indisponible", it) }
    }

    companion object {
        /** Un installateur de TV Slim pèse moins de cent mégaoctets ; au-delà, quelque chose cloche. */
        const val TAILLE_MAXIMALE = 400L * 1024 * 1024

        private const val TAG = "MiseAJour"
        private const val DELAI_LANCEMENT_S = 30L

        /**
         * L'application **et** son lanceur : jpackage fait tourner la JVM dans un processus enfant du
         * `TV Slim.exe` qu'on a ouvert, et ce parent tient l'exécutable que le MSI doit remplacer.
         */
        fun processusAAttendre(): List<Long> {
            val courant = ProcessHandle.current()
            val commande = courant.info().command().orElse("")
            val lanceur = courant.parent().orElse(null)?.takeIf { parent ->
                commande.isNotEmpty() && parent.info().command().orElse("").equals(commande, ignoreCase = true)
            }
            return listOfNotNull(courant.pid(), lanceur?.pid())
        }

        /** Fait créer le relais par WMI, et dit si Windows l'a accepté. */
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
         * Le relais, en PowerShell : attend la fin de l'application et de son lanceur, installe en
         * silence, note le code de `msiexec`, relance. Les chemins passent en littéraux entre
         * apostrophes ; aucun guillemet double — ni Java ni la ligne de commande transmise par WMI
         * ne les rendraient intacts. `[char]34` les produit une fois dans PowerShell.
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
         * Ce que l'application exécute : un PowerShell éphémère — lui mourra avec elle, peu importe —
         * qui demande à WMI de créer le relais, et rend le code de retour de WMI (0 : créé).
         * Le script du relais y est un littéral : ses apostrophes sont donc doublées une seconde fois.
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
