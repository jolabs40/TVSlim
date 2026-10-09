package net.jolabs40.tvslim.remote.ui

import android.content.Context
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
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.ClientAdb
import java.io.File

/** State of the "TV Slim on the TV" card. */
data class EtatApplicationTvUi(
    /** Null until the TV has been read. */
    val situation: SituationTv? = null,
    /** Current install step; null when idle. */
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
 * Reads which TV Slim app the TV has and installs it from GitHub (see the core's `ApplicationTv`).
 *
 * GitHub is queried at most once per app launch, and only when the card is shown (on a TV or a box).
 */
class PiloteApplicationTv(
    private val contexte: Context,
    private val client: ClientAdb,
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val installation: () -> InstallationApk?,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
    private val remercier: () -> Unit,
) {

    private val _etat = MutableStateFlow(EtatApplicationTvUi())
    val etat: StateFlow<EtatApplicationTvUi> = _etat.asStateFlow()

    private val source = SourceGithub(agent = "TVSlim-Remote/${BuildConfig.VERSION_NAME}")
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
            empreinteAttendue = BuildConfig.EMPREINTE_CERTIFICAT,
            dossier = File(contexte.cacheDir, "application-tv"),
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

    /** Drops what was read, on TV change or disconnection. */
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
            afficher(texte(resultat))
            if (resultat is ResultatTv.Reussi) remercier()
            // GitHub may have failed on the first read but answered during the install.
            if (derniere == null) derniere = application.derniere()
            _etat.update { it.copy(situation = application.situation(derniere)) }
        }
    }

    private fun texte(resultat: ResultatTv): String = when (resultat) {
        is ResultatTv.Reussi -> contexte.getString(
            when {
                !resultat.autorisee -> R.string.tvapp_done_no_permission
                resultat.gardien -> R.string.tvapp_done
                else -> R.string.tvapp_done_guardian_manual
            },
            resultat.version,
        )

        is ResultatTv.Echoue -> when (resultat.motif) {
            MotifTv.INSTALLATION -> contexte.getString(
                R.string.tvapp_error_install,
                contexte.getString(resultat.cause?.ressource() ?: R.string.apk_cause_other),
            )
            else -> contexte.getString(
                when (resultat.motif) {
                    MotifTv.INTROUVABLE -> R.string.tvapp_error_not_found
                    MotifTv.RESEAU -> R.string.tvapp_error_network
                    MotifTv.TROP_GROS -> R.string.tvapp_error_too_big
                    MotifTv.CERTIFICAT -> R.string.tvapp_error_certificate
                    MotifTv.PAQUET -> R.string.tvapp_error_package
                    MotifTv.ANDROID_TROP_ANCIEN -> R.string.tvapp_error_sdk
                    else -> R.string.tvapp_error_tv
                },
            )
        }
    }
}
