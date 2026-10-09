package net.jolabs40.tvslim.installation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.InstallateurApk
import net.jolabs40.tvslim.shell.ResultatShell
import java.io.File

/** Version of an app already on the TV. */
data class VersionInstallee(val versionCode: Long, val versionName: String)

/** What the installation will do, compared with what the TV already has. */
enum class NatureInstallation { NOUVELLE, MISE_A_JOUR, REINSTALLATION, RETROGRADATION }

/** A checked APK, ready for confirmation. */
data class ApkChoisi(
    val fichier: File,
    /** File name as picked by the user; on Android, [fichier] is only a copy. */
    val nom: String,
    val taille: Long,
    val manifeste: ManifesteApk,
    val installee: VersionInstallee?,
) {
    val nature: NatureInstallation
        get() = when {
            installee == null -> NatureInstallation.NOUVELLE
            manifeste.versionCode > installee.versionCode -> NatureInstallation.MISE_A_JOUR
            manifeste.versionCode == installee.versionCode -> NatureInstallation.REINSTALLATION
            else -> NatureInstallation.RETROGRADATION
        }
}

/** Why a file is rejected before anything is sent. */
enum class RefusApk { PAS_UN_APK, LOT, PAQUET_INVALIDE, ANDROID_TROP_ANCIEN, TELEVISEUR_INJOIGNABLE }

sealed interface ExamenApk {
    data class Pret(val apk: ApkChoisi) : ExamenApk

    data class Refuse(val refus: RefusApk, val minSdk: Int? = null, val sdkTeleviseur: Int? = null) : ExamenApk
}

/** The most common Android install failures, so they can be explained plainly. Each app words them. */
enum class CauseEchec {
    SIGNATURE_DIFFERENTE, RETROGRADATION, ANDROID_TROP_ANCIEN, ARCHITECTURE, ESPACE,
    NON_SIGNE, INCOMPLET, REFUSEE, INVALIDE, CONNEXION, AUTRE,
}

sealed interface ResultatInstallation {
    val apk: ApkChoisi

    data class Reussie(override val apk: ApkChoisi) : ResultatInstallation

    /** [detail]: the TV's raw answer. */
    data class Echouee(override val apk: ApkChoisi, val cause: CauseEchec, val detail: String) : ResultatInstallation
}

/**
 * Installs a user-picked APK on the TV, like `adb install`, with no PC needed for the companion and no
 * `adb.exe` for Windows.
 *
 * Two steps, like every TV Slim action: [examiner] reads the file and the TV without changing anything, so
 * the confirmation can say which app arrives and what it replaces; [installer] sends it, then journals it.
 * No undo command is recorded: the only one would be `pm uninstall`, which TV Slim never sends.
 */
class InstallationApk(
    private val executeur: ExecuteurCommande,
    private val installateur: InstallateurApk,
    private val journal: JournalRepository,
) {

    suspend fun examiner(fichier: File, nom: String): ExamenApk =
        when (val analyse = withContext(Dispatchers.IO) { FichierApk.analyser(fichier) }) {
            is AnalyseApk.Valide -> examiner(fichier, nom, analyse.manifeste)
            AnalyseApk.Lot -> ExamenApk.Refuse(RefusApk.LOT)
            AnalyseApk.PasUnApk -> ExamenApk.Refuse(RefusApk.PAS_UN_APK)
        }

    /** Checks an already-read manifest against the TV: its Android version, and the version installed. */
    internal suspend fun examiner(fichier: File, nom: String, manifeste: ManifesteApk): ExamenApk {
        // The package name goes into a command and comes from a file anyone could have crafted.
        if (!IDENTIFIANT.matches(manifeste.paquet)) return ExamenApk.Refuse(RefusApk.PAQUET_INVALIDE)

        val lecture = executeur.executer(
            "getprop ro.build.version.sdk; dumpsys package ${manifeste.paquet} | grep -E '^ +version(Code|Name)='",
        )
        // The exit code is grep's, 1 when the app is absent: only a broken connection counts here.
        if (lecture.code < 0) return ExamenApk.Refuse(RefusApk.TELEVISEUR_INJOIGNABLE)

        val sdk = lecture.sortie.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
        val minSdk = manifeste.minSdk
        if (sdk != null && minSdk != null && minSdk > sdk) {
            return ExamenApk.Refuse(RefusApk.ANDROID_TROP_ANCIEN, minSdk = minSdk, sdkTeleviseur = sdk)
        }
        return ExamenApk.Pret(
            ApkChoisi(
                fichier = fichier,
                nom = nom,
                taille = fichier.length(),
                manifeste = manifeste,
                installee = versionInstallee(lecture.sortie),
            ),
        )
    }

    suspend fun installer(
        apk: ApkChoisi,
        surEnvoi: (envoye: Long, total: Long) -> Unit = { _, _ -> },
    ): ResultatInstallation {
        val sortie = installateur.installer(apk.fichier, surEnvoi)
        val reussie = sortie.reussi && sortie.sortie.contains("Success")
        // Empty when the TV said nothing; the typed cause is then enough for the UI.
        val detail = sortie.sortie

        val version = apk.manifeste.versionName.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.INSTALLATION,
                cible = apk.manifeste.paquet,
                libelle = "Installation de ${apk.nom}$version",
                commandeAnnulation = "",
                reussi = reussie,
                message = if (reussie) "" else detail,
            ),
        )
        return if (reussie) {
            ResultatInstallation.Reussie(apk)
        } else {
            ResultatInstallation.Echouee(apk, causeEchec(sortie), detail)
        }
    }

    internal companion object {
        /** The TV shell takes the line as is, so only a plain identifier is allowed. */
        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        private val VERSION_CODE = Regex("""versionCode=(\d+)""")
        private val VERSION_NAME = Regex("""versionName=(.*)""")
        private val CODE_ANDROID = Regex("""INSTALL_[A-Z_]+""")

        /**
         * The first version found is the installed one: for an updated system app, `dumpsys` then lists
         * the factory version hidden behind it.
         */
        fun versionInstallee(sortie: String): VersionInstallee? {
            val code = VERSION_CODE.find(sortie)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val nom = VERSION_NAME.find(sortie)?.groupValues?.get(1)?.trim().orEmpty()
            return VersionInstallee(code, nom)
        }

        fun causeEchec(sortie: ResultatShell): CauseEchec {
            if (sortie.code < 0) return CauseEchec.CONNEXION
            val code = CODE_ANDROID.find(sortie.sortie)?.value ?: return CauseEchec.AUTRE
            return when (code) {
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE",
                "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES",
                -> CauseEchec.SIGNATURE_DIFFERENTE

                "INSTALL_FAILED_VERSION_DOWNGRADE" -> CauseEchec.RETROGRADATION
                "INSTALL_FAILED_OLDER_SDK" -> CauseEchec.ANDROID_TROP_ANCIEN
                "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> CauseEchec.ARCHITECTURE
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> CauseEchec.ESPACE
                "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> CauseEchec.NON_SIGNE
                "INSTALL_FAILED_MISSING_SPLIT" -> CauseEchec.INCOMPLET

                // Play Protect, a verification that does not complete, or a refusal on the remote.
                "INSTALL_FAILED_VERIFICATION_FAILURE",
                "INSTALL_FAILED_VERIFICATION_TIMEOUT",
                "INSTALL_FAILED_ABORTED",
                "INSTALL_FAILED_USER_RESTRICTED",
                -> CauseEchec.REFUSEE

                "INSTALL_FAILED_INVALID_APK" -> CauseEchec.INVALIDE
                else -> if (code.startsWith("INSTALL_PARSE_FAILED")) CauseEchec.INVALIDE else CauseEchec.AUTRE
            }
        }
    }
}
