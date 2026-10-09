package net.jolabs40.tvslim.applicationtv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.installation.CauseEchec
import net.jolabs40.tvslim.installation.ExamenApk
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.installation.RefusApk
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.installation.SignatureApk
import net.jolabs40.tvslim.installation.VersionInstallee
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.shell.ExecuteurCommande
import java.io.File
import java.io.IOException

/** State of the TV Slim app on the TV. */
enum class EtatApplicationTv { ABSENTE, MISE_A_JOUR, SANS_AUTORISATION, A_JOUR }

data class SituationTv(
    val installee: VersionInstallee? = null,
    /** `WRITE_SECURE_SETTINGS` granted; without it the boot guard cannot reapply anything. */
    val autorisee: Boolean = false,
    /** Latest release, if GitHub answered. */
    val disponible: PublicationTv? = null,
) {
    val etat: EtatApplicationTv
        get() = when {
            installee == null -> EtatApplicationTv.ABSENTE
            disponible != null && disponible.versionCode > installee.versionCode -> EtatApplicationTv.MISE_A_JOUR
            !autorisee -> EtatApplicationTv.SANS_AUTORISATION
            else -> EtatApplicationTv.A_JOUR
        }
}

/** Current installation step, for the progress bar. */
sealed interface EtapeTv {
    data object Recherche : EtapeTv
    data class Telechargement(val recus: Long, val total: Long) : EtapeTv
    data object Verification : EtapeTv
    data class Envoi(val envoye: Long, val total: Long) : EtapeTv
    data object Autorisation : EtapeTv
    data object Gardien : EtapeTv
}

/** Why nothing was installed, or why the installation stopped midway. */
enum class MotifTv {
    INTROUVABLE, RESEAU, TROP_GROS, CERTIFICAT, PAQUET, ANDROID_TROP_ANCIEN, TELEVISEUR_INJOIGNABLE, INSTALLATION,
}

sealed interface ResultatTv {
    /**
     * Installed. [autorisee]: `WRITE_SECURE_SETTINGS` granted. [gardien]: the boot guard confirmed it is on; false
     * with a TV app too old to know the command (1.0.0).
     */
    data class Reussi(val version: String, val autorisee: Boolean, val gardien: Boolean) : ResultatTv

    data class Echoue(val motif: MotifTv, val cause: CauseEchec? = null, val detail: String = "") : ResultatTv
}

/**
 * Installs the TV app from the latest GitHub release in one go: download, check the APK is ours, install,
 * grant what only ADB can grant, launch it, and turn on its boot guard.
 *
 * Nothing is sent to the TV before the APK certificate matches [empreinteAttendue] (`empreinteCertificat`,
 * which CI also checks before publishing).
 */
class ApplicationTv(
    private val executeur: ExecuteurCommande,
    private val installation: InstallationApk,
    private val moteur: MoteurDebloat,
    private val lecteur: LecteurDistant,
    private val source: SourcePublications,
    private val empreinteAttendue: String,
    /** Where the APK is kept while it is sent; the file is always deleted afterwards. */
    private val dossier: File,
) {

    /** Reads what the TV has, compared with [disponible] (the latest release from [derniere], or null). */
    suspend fun situation(disponible: PublicationTv?): SituationTv {
        val versions = executeur.executer("dumpsys package $PAQUET | grep -E '^ +version(Code|Name)='")
        val installee = InstallationApk.versionInstallee(versions.sortie)
        val autorisee = installee != null && WRITE_SECURE_SETTINGS in lecteur.permissions(PAQUET).accordees
        return SituationTv(installee, autorisee, disponible)
    }

    /** Latest TV app release on GitHub, or null when GitHub does not answer. */
    suspend fun derniere(): PublicationTv? = try {
        ChoixPublicationTv.choisir(source.publications())
    } catch (annulation: CancellationException) {
        throw annulation
    } catch (_: Exception) {
        null
    }

    suspend fun installer(surEtape: (EtapeTv) -> Unit = {}): ResultatTv {
        val apk = File(dossier, NOM_LOCAL)
        try {
            surEtape(EtapeTv.Recherche)
            val publication = try {
                ChoixPublicationTv.choisir(source.publications())
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (_: IOException) {
                return ResultatTv.Echoue(MotifTv.RESEAU)
            } ?: return ResultatTv.Echoue(MotifTv.INTROUVABLE)

            try {
                source.telecharger(publication.url, apk, TAILLE_MAX) { recus, total ->
                    surEtape(EtapeTv.Telechargement(recus, if (total > 0) total else publication.taille))
                }
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (_: TelechargementTropGros) {
                return ResultatTv.Echoue(MotifTv.TROP_GROS)
            } catch (_: IOException) {
                return ResultatTv.Echoue(MotifTv.RESEAU)
            }

            surEtape(EtapeTv.Verification)
            val empreinte = withContext(Dispatchers.IO) { SignatureApk.empreinteCertificat(apk) }
            if (empreinte == null || !empreinte.equals(empreinteAttendue, ignoreCase = true)) {
                return ResultatTv.Echoue(MotifTv.CERTIFICAT, detail = empreinte.orEmpty())
            }
            val choisi = when (val examen = installation.examiner(apk, publication.nomFichier)) {
                is ExamenApk.Pret -> examen.apk
                is ExamenApk.Refuse -> return ResultatTv.Echoue(
                    when (examen.refus) {
                        RefusApk.ANDROID_TROP_ANCIEN -> MotifTv.ANDROID_TROP_ANCIEN
                        RefusApk.TELEVISEUR_INJOIGNABLE -> MotifTv.TELEVISEUR_INJOIGNABLE
                        else -> MotifTv.PAQUET
                    },
                )
            }
            if (choisi.manifeste.paquet != PAQUET) return ResultatTv.Echoue(MotifTv.PAQUET, detail = choisi.manifeste.paquet)

            when (val envoi = installation.installer(choisi) { envoye, total -> surEtape(EtapeTv.Envoi(envoye, total)) }) {
                is ResultatInstallation.Echouee ->
                    return ResultatTv.Echoue(MotifTv.INSTALLATION, envoi.cause, envoi.detail)
                is ResultatInstallation.Reussie -> Unit
            }

            return finaliser(publication.version, surEtape)
        } finally {
            withContext(Dispatchers.IO) { apk.delete() }
        }
    }

    /** Runs the post-install steps (grant, launch, boot guard) for a TV app that is already installed. */
    suspend fun autoriser(version: String, surEtape: (EtapeTv) -> Unit = {}): ResultatTv = finaliser(version, surEtape)

    private suspend fun finaliser(version: String, surEtape: (EtapeTv) -> Unit): ResultatTv.Reussi {
        surEtape(EtapeTv.Autorisation)
        val autorisee = accorder()

        // An app never opened stays in the stopped state and does not receive BOOT_COMPLETED, so the guard
        // would never run. Launch it only when stopped: an update keeps it out of that state, and opening it
        // in the foreground would interrupt whatever the TV is showing.
        surEtape(EtapeTv.Gardien)
        if (arretee(executeur.executer(COMMANDE_ETAT).sortie)) executeur.executer("am start -n $PAQUET/.MainActivity")
        val gardien = executeur.executer(COMMANDE_GARDIEN).sortie.contains("result=$GARDIEN_ACTIVE")
        return ResultatTv.Reussi(version, autorisee, gardien)
    }

    /**
     * Grants `WRITE_SECURE_SETTINGS` and, on Android 13+, `POST_NOTIFICATIONS` so the TV app can report drift.
     * Both go through the engine, so each is journaled with its undo command.
     */
    private suspend fun accorder(): Boolean {
        val permissions = lecteur.permissions(PAQUET)
        val ecriture = WRITE_SECURE_SETTINGS in permissions.accordees ||
            moteur.accorderPermission(PAQUET, WRITE_SECURE_SETTINGS, permissions.demandees).reussi
        val sdk = executeur.executer("getprop ro.build.version.sdk").sortie.trim().toIntOrNull() ?: 0
        if (sdk >= 33 && POST_NOTIFICATIONS in permissions.demandees && POST_NOTIFICATIONS !in permissions.accordees) {
            moteur.accorderPermission(PAQUET, POST_NOTIFICATIONS, permissions.demandees)
        }
        return ecriture
    }

    companion object {
        const val PAQUET = "net.jolabs40.tvslim"
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        /**
         * The TV app's receiver only accepts this broadcast from a holder of `WRITE_SECURE_SETTINGS` (the ADB
         * shell, not another app). It replies with result [GARDIEN_ACTIVE] once the guard is on.
         */
        const val ACTION_GARDIEN = "net.jolabs40.tvslim.action.ACTIVER_GARDIEN"
        const val GARDIEN_ACTIVE = 1
        const val COMMANDE_GARDIEN = "am broadcast -a $ACTION_GARDIEN -n $PAQUET/.system.ActivationGardienReceiver"

        /** Package state for user 0: `User 0: ... stopped=false notLaunched=false ...`. */
        const val COMMANDE_ETAT = "dumpsys package $PAQUET | grep -E '^ +User 0:'"

        /**
         * True if the app is stopped for user 0 (never opened, or force-stopped). Only user 0 counts: the TCL's
         * second profile, never opened, reports it stopped forever. Unreadable output counts as stopped: a needless
         * launch costs one screen, a missed one costs the guard.
         */
        fun arretee(sortie: String): Boolean {
            val ligne = sortie.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("User 0:") && "stopped=" in it }
                ?: return true
            return "stopped=true" in ligne || "notLaunched=true" in ligne
        }

        private const val NOM_LOCAL = "tvslim-tv.apk"

        /** The TV APK is about 1.3 MB; 50 MB leaves headroom without accepting anything. */
        const val TAILLE_MAX = 50L * 1024 * 1024
    }
}
