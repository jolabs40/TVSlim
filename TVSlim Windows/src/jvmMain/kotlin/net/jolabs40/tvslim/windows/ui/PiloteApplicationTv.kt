package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.applicationtv.ApplicationTv
import net.jolabs40.tvslim.applicationtv.EtapeTv
import net.jolabs40.tvslim.applicationtv.MotifTv
import net.jolabs40.tvslim.applicationtv.PublicationTv
import net.jolabs40.tvslim.applicationtv.ResultatTv
import net.jolabs40.tvslim.applicationtv.SituationTv
import net.jolabs40.tvslim.applicationtv.SourceGithub
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.windows.InfosApp
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.apk_cause_other
import net.jolabs40.tvslim.windows.ressources.tvapp_done
import net.jolabs40.tvslim.windows.ressources.tvapp_done_guardian_manual
import net.jolabs40.tvslim.windows.ressources.tvapp_done_no_permission
import net.jolabs40.tvslim.windows.ressources.tvapp_error_certificate
import net.jolabs40.tvslim.windows.ressources.tvapp_error_install
import net.jolabs40.tvslim.windows.ressources.tvapp_error_network
import net.jolabs40.tvslim.windows.ressources.tvapp_error_not_found
import net.jolabs40.tvslim.windows.ressources.tvapp_error_package
import net.jolabs40.tvslim.windows.ressources.tvapp_error_sdk
import net.jolabs40.tvslim.windows.ressources.tvapp_error_too_big
import net.jolabs40.tvslim.windows.ressources.tvapp_error_tv
import java.io.File

/** La carte « TV Slim sur le téléviseur ». */
data class EtatApplicationTvUi(
    /** Null tant que le téléviseur n'a pas été lu. */
    val situation: SituationTv? = null,
    /** Non nulle pendant une installation : ce qu'elle fait. */
    val etape: EtapeTv? = null,
) {
    val occupee: Boolean get() = etape != null
}

data class ActionsApplicationTv(
    val onLire: () -> Unit,
    val onInstaller: () -> Unit,
    val onAutoriser: () -> Unit,
)

/**
 * L'application TV Slim du téléviseur : ce qu'il en porte, et son installation depuis GitHub — cf.
 * `ApplicationTv` du noyau. Le même pilote que sur le téléphone ; GitHub n'est consulté que quand la
 * carte s'affiche, face à un téléviseur ou une box, et une fois par lancement.
 */
class PiloteApplicationTv(
    private val client: ClientAdb,
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val installation: () -> InstallationApk?,
    private val dossier: File,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    private val remercier: () -> Unit,
) {

    private val _etat = MutableStateFlow(EtatApplicationTvUi())
    val etat: StateFlow<EtatApplicationTvUi> = _etat.asStateFlow()

    private val source = SourceGithub(agent = "TVSlim-Windows/${InfosApp.VERSION}")
    private var derniere: PublicationTv? = null
    private var demandee = false
    private var travail: Job? = null

    private fun application(): ApplicationTv? {
        val moteurActif = moteur() ?: return null
        val installationActive = installation() ?: return null
        return ApplicationTv(
            executeur = client,
            installation = installationActive,
            moteur = moteurActif,
            lecteur = lecteur,
            source = source,
            empreinteAttendue = InfosApp.EMPREINTE_CERTIFICAT_ANDROID,
            dossier = dossier,
        )
    }

    fun lire() {
        val application = application() ?: return
        if (travail?.isActive == true) return
        travail = portee.launch {
            if (!demandee) {
                demandee = true
                derniere = application.derniere()
            }
            val situation = application.situation(derniere)
            _etat.update { it.copy(situation = situation) }
        }
    }

    fun installer() = lancer { application, etape -> application.installer(etape) }

    fun autoriser() {
        val version = _etat.value.situation?.installee?.versionName ?: return
        lancer { application, etape -> application.autoriser(version, etape) }
    }

    /** Le téléviseur change, ou la connexion tombe : rien de ce qu'on en a lu ne vaut plus. */
    fun oublier() {
        travail?.cancel()
        _etat.value = EtatApplicationTvUi()
    }

    private fun lancer(action: suspend (ApplicationTv, (EtapeTv) -> Unit) -> ResultatTv) {
        val application = application() ?: return
        if (_etat.value.occupee) return
        travail?.cancel()
        travail = portee.launch {
            _etat.update { it.copy(etape = EtapeTv.Recherche) }
            val resultat = try {
                action(application) { etape -> _etat.update { it.copy(etape = etape) } }
            } finally {
                _etat.update { it.copy(etape = null) }
            }
            afficher(message(resultat))
            if (resultat is ResultatTv.Reussi) remercier()
            // GitHub n'avait pas répondu à la première lecture : l'installation, elle, a pu le joindre.
            if (derniere == null) derniere = application.derniere()
            _etat.update { it.copy(situation = application.situation(derniere)) }
        }
    }

    private fun message(resultat: ResultatTv): MessageUi = when (resultat) {
        is ResultatTv.Reussi -> texte(
            when {
                !resultat.autorisee -> Res.string.tvapp_done_no_permission
                resultat.gardien -> Res.string.tvapp_done
                else -> Res.string.tvapp_done_guardian_manual
            },
            resultat.version,
        )

        is ResultatTv.Echoue -> when (resultat.motif) {
            MotifTv.INSTALLATION -> texte(
                Res.string.tvapp_error_install,
                texte(resultat.cause?.ressource() ?: Res.string.apk_cause_other),
            )
            MotifTv.INTROUVABLE -> texte(Res.string.tvapp_error_not_found)
            MotifTv.RESEAU -> texte(Res.string.tvapp_error_network)
            MotifTv.TROP_GROS -> texte(Res.string.tvapp_error_too_big)
            MotifTv.CERTIFICAT -> texte(Res.string.tvapp_error_certificate)
            MotifTv.PAQUET -> texte(Res.string.tvapp_error_package)
            MotifTv.ANDROID_TROP_ANCIEN -> texte(Res.string.tvapp_error_sdk)
            MotifTv.TELEVISEUR_INJOIGNABLE -> texte(Res.string.tvapp_error_tv)
        }
    }
}
