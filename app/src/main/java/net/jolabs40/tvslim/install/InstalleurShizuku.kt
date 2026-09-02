package net.jolabs40.tvslim.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installation intégrée de Shizuku : chercher la dernière version, la télécharger, **vérifier
 * sa signature**, puis la soumettre à l'installeur du système.
 *
 * La vérification n'est pas contournable : un APK dont le certificat ne correspond pas à
 * l'empreinte épinglée est supprimé sans être présenté à l'installeur. Et même une fois
 * l'APK admis, c'est le système qui demande confirmation — l'application ne peut rien
 * installer d'elle-même.
 */
@Singleton
class InstalleurShizuku @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val telechargement: TelechargementShizuku,
    private val verificateur: VerificateurSignature,
) {

    val etat: StateFlow<EtatInstallation> = SuiviInstallation.etat.asStateFlow()

    /** L'installation intégrée exige de pouvoir lire la signature d'un APK : Android 9 au moins. */
    val disponible: Boolean get() = verificateur.verificationPossible

    fun sourcesInconnuesAutorisees(): Boolean =
        contexte.packageManager.canRequestPackageInstalls()

    /** Écran système où autoriser cette application à installer des paquets. */
    fun intentionSourcesInconnues(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${contexte.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun installer() {
        if (!disponible) {
            SuiviInstallation.publier(
                PhaseInstallation.ECHEC,
                detail = "Signature non vérifiable sur Android ${Build.VERSION.RELEASE}.",
            )
            return
        }
        if (!sourcesInconnuesAutorisees()) {
            SuiviInstallation.publier(
                PhaseInstallation.ECHEC,
                detail = "Cette application n'est pas autorisée à installer des paquets.",
            )
            return
        }

        val fichier = try {
            SuiviInstallation.publier(PhaseInstallation.RECHERCHE)
            val asset = telechargement.dernierApk()

            SuiviInstallation.publier(PhaseInstallation.TELECHARGEMENT, detail = asset.name)
            telechargement.telecharger(asset) { pourcent ->
                SuiviInstallation.publier(
                    PhaseInstallation.TELECHARGEMENT,
                    pourcent = pourcent,
                    detail = asset.name,
                )
            }
        } catch (erreur: Exception) {
            SuiviInstallation.publier(
                PhaseInstallation.ECHEC,
                detail = erreur.message ?: erreur.javaClass.simpleName,
            )
            return
        }

        SuiviInstallation.publier(PhaseInstallation.VERIFICATION)
        val verdict = verificateur.verifierApk(
            chemin = fichier.absolutePath,
            paquetAttendu = SourceShizuku.PAQUET,
            empreinteAttendue = SourceShizuku.EMPREINTE_CERTIFICAT,
        )
        if (verdict is Verdict.Refuse) {
            fichier.delete()
            SuiviInstallation.publier(PhaseInstallation.ECHEC, detail = verdict.motif)
            return
        }

        soumettre(fichier)
    }

    private suspend fun soumettre(fichier: File) = withContext(Dispatchers.IO) {
        val installateur = contexte.packageManager.packageInstaller
        var identifiant = -1
        try {
            val parametres = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply { setAppPackageName(SourceShizuku.PAQUET) }

            identifiant = installateur.createSession(parametres)
            installateur.openSession(identifiant).use { session ->
                session.openWrite(NOM_MORCEAU, 0, fichier.length()).use { sortie ->
                    fichier.inputStream().use { entree -> entree.copyTo(sortie) }
                    session.fsync(sortie)
                }
                session.commit(intentionResultat().intentSender)
            }
        } catch (erreur: Exception) {
            if (identifiant >= 0) runCatching { installateur.abandonSession(identifiant) }
            SuiviInstallation.publier(
                PhaseInstallation.ECHEC,
                detail = erreur.message ?: erreur.javaClass.simpleName,
            )
        } finally {
            fichier.delete()
        }
    }

    private fun intentionResultat(): PendingIntent {
        val intention = Intent(contexte, InstallationReceiver::class.java)
        // FLAG_MUTABLE : le système ajoute lui-même le statut et l'intention de confirmation.
        val drapeaux = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(contexte, CODE_RESULTAT, intention, drapeaux)
    }

    fun reinitialiser() = SuiviInstallation.publier(PhaseInstallation.INACTIVE)

    private companion object {
        const val NOM_MORCEAU = "shizuku"
        const val CODE_RESULTAT = 7710
    }
}
