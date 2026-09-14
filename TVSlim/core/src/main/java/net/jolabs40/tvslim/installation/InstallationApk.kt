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

/** La version d'une application déjà présente sur le téléviseur. */
data class VersionInstallee(val versionCode: Long, val versionName: String)

/** Ce que l'installation va faire, comparée à ce que le téléviseur porte déjà. */
enum class NatureInstallation { NOUVELLE, MISE_A_JOUR, REINSTALLATION, RETROGRADATION }

/** Un APK examiné, prêt à être soumis à confirmation. */
data class ApkChoisi(
    val fichier: File,
    /** Le nom du fichier tel que la personne l'a choisi : sur Android, [fichier] n'en est qu'une copie. */
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

/** Pourquoi un fichier est écarté avant que rien ne parte. */
enum class RefusApk { PAS_UN_APK, LOT, PAQUET_INVALIDE, ANDROID_TROP_ANCIEN, TELEVISEUR_INJOIGNABLE }

sealed interface ExamenApk {
    data class Pret(val apk: ApkChoisi) : ExamenApk

    data class Refuse(val refus: RefusApk, val minSdk: Int? = null, val sdkTeleviseur: Int? = null) : ExamenApk
}

/** Les refus d'Android les plus courants, pour les dire en clair. Chaque application les rédige. */
enum class CauseEchec {
    SIGNATURE_DIFFERENTE, RETROGRADATION, ANDROID_TROP_ANCIEN, ARCHITECTURE, ESPACE,
    NON_SIGNE, INCOMPLET, REFUSEE, INVALIDE, CONNEXION, AUTRE,
}

sealed interface ResultatInstallation {
    val apk: ApkChoisi

    data class Reussie(override val apk: ApkChoisi) : ResultatInstallation

    /** [detail] : ce que le téléviseur a répondu, tel quel. */
    data class Echouee(override val apk: ApkChoisi, val cause: CauseEchec, val detail: String) : ResultatInstallation
}

/**
 * Installe sur le téléviseur un APK choisi par la personne — ce que fait `adb install`, sans ordinateur
 * pour le compagnon et sans `adb.exe` pour Windows.
 *
 * Deux temps, comme toute action de TV Slim : [examiner] lit le fichier et le téléviseur sans rien
 * changer, pour que la confirmation dise quelle application arrive et ce qu'elle remplace ; [installer]
 * envoie, puis consigne au journal. Aucune commande d'annulation n'y figure : la seule serait
 * `pm uninstall`, que TV Slim n'envoie jamais.
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

    /** Confronte un manifeste déjà lu au téléviseur : son Android, et la version qu'il porte déjà. */
    internal suspend fun examiner(fichier: File, nom: String, manifeste: ManifesteApk): ExamenApk {
        // Le paquet part dans une commande, et il vient d'un fichier que n'importe qui a pu fabriquer.
        if (!IDENTIFIANT.matches(manifeste.paquet)) return ExamenApk.Refuse(RefusApk.PAQUET_INVALIDE)

        val lecture = executeur.executer(
            "getprop ro.build.version.sdk; dumpsys package ${manifeste.paquet} | grep -E '^ +version(Code|Name)='",
        )
        // Le code de sortie est celui de grep, qui vaut 1 pour une application absente : seule une
        // connexion rompue compte ici.
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
        val detail = sortie.sortie.ifBlank { "Échec inexpliqué." }

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
        /** Le shell du téléviseur prend la ligne telle quelle : un nom, et rien d'autre. */
        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        private val VERSION_CODE = Regex("""versionCode=(\d+)""")
        private val VERSION_NAME = Regex("""versionName=(.*)""")
        private val CODE_ANDROID = Regex("""INSTALL_[A-Z_]+""")

        /**
         * La première version lue est celle de l'application en place : `dumpsys` liste ensuite, pour
         * une application système mise à jour, la version d'usine cachée derrière elle.
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

                // Play Protect, une vérification qui n'aboutit pas, ou un refus à la télécommande.
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
