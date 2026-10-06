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

/** Où en est l'application TV Slim du téléviseur. */
enum class EtatApplicationTv { ABSENTE, MISE_A_JOUR, SANS_AUTORISATION, A_JOUR }

data class SituationTv(
    val installee: VersionInstallee? = null,
    /** `WRITE_SECURE_SETTINGS` accordée : sans elle, le gardien ne réapplique rien. */
    val autorisee: Boolean = false,
    /** La dernière publication, si GitHub a répondu. */
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

/** Ce que l'installation est en train de faire, pour la barre d'avancement. */
sealed interface EtapeTv {
    data object Recherche : EtapeTv
    data class Telechargement(val recus: Long, val total: Long) : EtapeTv
    data object Verification : EtapeTv
    data class Envoi(val envoye: Long, val total: Long) : EtapeTv
    data object Autorisation : EtapeTv
    data object Gardien : EtapeTv
}

/** Pourquoi rien n'a été installé — ou pourquoi l'installation s'est arrêtée en route. */
enum class MotifTv {
    INTROUVABLE, RESEAU, TROP_GROS, CERTIFICAT, PAQUET, ANDROID_TROP_ANCIEN, TELEVISEUR_INJOIGNABLE, INSTALLATION,
}

sealed interface ResultatTv {
    /**
     * Installée. [autorisee] : `WRITE_SECURE_SETTINGS` accordée ; [gardien] : le gardien a confirmé son
     * activation — faux face à une application TV qui ne connaît pas encore la commande (1.0.0).
     */
    data class Reussi(val version: String, val autorisee: Boolean, val gardien: Boolean) : ResultatTv

    data class Echoue(val motif: MotifTv, val cause: CauseEchec? = null, val detail: String = "") : ResultatTv
}

/**
 * L'application TV Slim du téléviseur, installée depuis la dernière publication GitHub, en une fois :
 * télécharger, vérifier que l'APK est bien le nôtre, l'installer, lui accorder ce que seul ADB accorde,
 * la lancer, et allumer son gardien de démarrage.
 *
 * Rien ne part vers le téléviseur avant que le certificat de l'APK n'ait été comparé à [empreinteAttendue]
 * — celle de `empreinteCertificat`, que la CI vérifie aussi avant de publier.
 */
class ApplicationTv(
    private val executeur: ExecuteurCommande,
    private val installation: InstallationApk,
    private val moteur: MoteurDebloat,
    private val lecteur: LecteurDistant,
    private val source: SourcePublications,
    private val empreinteAttendue: String,
    /** Où poser l'APK le temps de l'envoyer : effacé ensuite, quoi qu'il arrive. */
    private val dossier: File,
) {

    /**
     * Ce que porte le téléviseur, comparé à [disponible] — la dernière publication, que l'appelant a
     * demandée à GitHub ([derniere]) ou non : sans elle, la situation reste lisible.
     */
    suspend fun situation(disponible: PublicationTv?): SituationTv {
        val versions = executeur.executer("dumpsys package $PAQUET | grep -E '^ +version(Code|Name)='")
        val installee = InstallationApk.versionInstallee(versions.sortie)
        val autorisee = installee != null && WRITE_SECURE_SETTINGS in lecteur.permissions(PAQUET).accordees
        return SituationTv(installee, autorisee, disponible)
    }

    /** La dernière application TV publiée sur GitHub ; null s'il ne répond pas. */
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

    /**
     * Pour une application TV déjà installée : ce qui suit l'installation, sans rien retélécharger —
     * l'autorisation qui lui manque, un lancement, et le gardien.
     */
    suspend fun autoriser(version: String, surEtape: (EtapeTv) -> Unit = {}): ResultatTv = finaliser(version, surEtape)

    private suspend fun finaliser(version: String, surEtape: (EtapeTv) -> Unit): ResultatTv.Reussi {
        surEtape(EtapeTv.Autorisation)
        val autorisee = accorder()

        // Lancée une fois, elle quitte l'état « arrêtée » où Android laisse une application jamais
        // ouverte — et qui ne reçoit pas BOOT_COMPLETED : sans ce lancement, le gardien dormirait.
        surEtape(EtapeTv.Gardien)
        executeur.executer("am start -n $PAQUET/.MainActivity")
        val gardien = executeur.executer(COMMANDE_GARDIEN).sortie.contains("result=$GARDIEN_ACTIVE")
        return ResultatTv.Reussi(version, autorisee, gardien)
    }

    /**
     * `WRITE_SECURE_SETTINGS`, la raison d'être de l'application TV, et l'affichage des notifications
     * qu'Android 13 demande pour qu'elle prévienne d'une dérive. Toutes deux passent par le moteur, donc
     * au journal, chacune avec sa commande d'annulation.
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
         * Le récepteur de l'application TV n'accepte que ce que lui envoie un détenteur de
         * `WRITE_SECURE_SETTINGS` — le shell d'ADB, pas une application du téléviseur. Il répond par ce
         * code quand le gardien est allumé.
         */
        const val ACTION_GARDIEN = "net.jolabs40.tvslim.action.ACTIVER_GARDIEN"
        const val GARDIEN_ACTIVE = 1
        const val COMMANDE_GARDIEN = "am broadcast -a $ACTION_GARDIEN -n $PAQUET/.system.ActivationGardienReceiver"

        private const val NOM_LOCAL = "tvslim-tv.apk"

        /** L'APK du téléviseur pèse 1,3 Mo : cinquante laissent de la marge, sans laisser tout passer. */
        const val TAILLE_MAX = 50L * 1024 * 1024
    }
}
