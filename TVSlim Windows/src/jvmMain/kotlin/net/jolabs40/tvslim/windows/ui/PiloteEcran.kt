package net.jolabs40.tvslim.windows.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.ecran.Arret
import net.jolabs40.tvslim.ecran.CaptureEcran
import net.jolabs40.tvslim.ecran.CauseCapture
import net.jolabs40.tvslim.ecran.CauseEnregistrement
import net.jolabs40.tvslim.ecran.Demarrage
import net.jolabs40.tvslim.ecran.EnregistrementTv
import net.jolabs40.tvslim.ecran.ResultatCapture
import net.jolabs40.tvslim.shell.LecteurBinaire
import net.jolabs40.tvslim.windows.ecran.ArgumentsScrcpy
import net.jolabs40.tvslim.windows.ecran.EmpreinteInattendue
import net.jolabs40.tvslim.windows.ecran.InstallationScrcpy
import net.jolabs40.tvslim.windows.ecran.LocalisationScrcpy
import net.jolabs40.tvslim.windows.ecran.PressePapiers
import net.jolabs40.tvslim.windows.ecran.SessionScrcpy
import net.jolabs40.tvslim.windows.outils.Traces
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.capture_copied
import net.jolabs40.tvslim.windows.ressources.capture_failed_connection
import net.jolabs40.tvslim.windows.ressources.capture_failed_refused
import net.jolabs40.tvslim.windows.ressources.capture_failed_unreadable
import net.jolabs40.tvslim.windows.ressources.capture_write_failed
import net.jolabs40.tvslim.windows.ressources.msg_connect_first
import net.jolabs40.tvslim.windows.ressources.record_copy_failed
import net.jolabs40.tvslim.windows.ressources.record_empty
import net.jolabs40.tvslim.windows.ressources.record_failed_start
import net.jolabs40.tvslim.windows.ressources.record_limit
import net.jolabs40.tvslim.windows.ressources.record_unavailable
import net.jolabs40.tvslim.windows.ressources.scrcpy_download_failed
import net.jolabs40.tvslim.windows.ressources.scrcpy_failed
import net.jolabs40.tvslim.windows.ressources.scrcpy_fingerprint_failed
import net.jolabs40.tvslim.windows.ressources.scrcpy_unauthorized
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime

/** Target TV: address for scrcpy, device info for file names. */
data class CibleEcran(val hote: String, val port: Int, val infos: InfosAppareil)

/** A saved screenshot, shown in a preview. */
class CaptureFaite(val fichier: File, val png: ByteArray, val largeur: Int, val hauteur: Int)

sealed interface PhaseScrcpy {
    data object Inactif : PhaseScrcpy

    /** scrcpy was missing and is being downloaded from GitHub. */
    data class Telechargement(val progression: Float) : PhaseScrcpy

    /** Launched; its window is opening or open. */
    data object Actif : PhaseScrcpy
}

sealed interface PhaseEnregistrement {
    data object Inactif : PhaseEnregistrement

    data object Demarrage : PhaseEnregistrement

    /** [limiteS]: time limit before Android 14, after which the recorder stops on its own. */
    data class EnCours(val debut: Long, val limiteS: Int?) : PhaseEnregistrement

    /** The recorder is stopping and finishing the video file on the TV. */
    data object Arret : PhaseEnregistrement

    data class Copie(val progression: Float) : PhaseEnregistrement
}

data class EtatEcran(
    val captureEnCours: Boolean = false,
    val capture: CaptureFaite? = null,
    val scrcpy: PhaseScrcpy = PhaseScrcpy.Inactif,
    /** Title of the requested mirror while the scrcpy download is being offered. */
    val telechargementPropose: String? = null,
    val enregistrement: PhaseEnregistrement = PhaseEnregistrement.Inactif,
    /** Video just copied to the PC. */
    val video: File? = null,
    /** TV Slim quits as soon as the current video has arrived. */
    val fermeture: Boolean = false,
    val message: MessageUi? = null,
)

/**
 * The TV screen from the PC:
 *
 * - screenshot, over TV Slim's ADB session;
 * - video, recorded on the TV by its own `screenrecord`, then copied over the same session (`EnregistrementTv`),
 *   without audio;
 * - mirror, through scrcpy, which opens its own window and session with its own key, so the TV asks once to
 *   authorize it. TV Slim does not lend its own key, which never leaves the app decrypted.
 *
 * Recording closes the mirror: one or the other, never both.
 */
class PiloteEcran(
    private val lecteur: LecteurBinaire,
    private val enregistrement: EnregistrementTv,
    private val localisation: LocalisationScrcpy,
    private val installation: InstallationScrcpy,
    /** `null` while no TV is connected. */
    private val cible: () -> CibleEcran?,
    private val dossierImages: () -> File,
    private val dossierVideos: () -> File,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatEcran())
    val etat: StateFlow<EtatEcran> = _etat.asStateFlow()

    private var session: SessionScrcpy? = null

    /** Watches for the recorder ending: limit reached or unexpected stop. */
    private var veille: Job? = null
    private var fin: Job? = null

    /** TV info and start time of the recording, used to name the video. */
    private var enregistre: Pair<InfosAppareil, LocalDateTime>? = null

    private var quitter: (() -> Unit)? = null

    // --- Screenshot -------------------------------------------------------------------------

    fun capturer() {
        val visee = cible() ?: return afficher(texte(Res.string.msg_connect_first))
        if (_etat.value.captureEnCours) return
        _etat.update { it.copy(captureEnCours = true) }
        viewModelScope.launch {
            val message = when (val resultat = CaptureEcran(lecteur).capturer()) {
                is ResultatCapture.Reussie -> enregistrerCapture(resultat, visee.infos)
                is ResultatCapture.Echouee -> messageEchec(resultat)
            }
            _etat.update { it.copy(captureEnCours = false) }
            message?.let(::afficher)
        }
    }

    /** Writes the screenshot to `Pictures\TV Slim`; returns a message only if writing fails. */
    private suspend fun enregistrerCapture(resultat: ResultatCapture.Reussie, infos: InfosAppareil): MessageUi? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dossier = File(dossierImages(), SOUS_DOSSIER).apply { mkdirs() }
                val fichier = File(dossier, CaptureEcran.nomFichier(infos, LocalDateTime.now(), "png"))
                fichier.writeBytes(resultat.png)
                CaptureFaite(fichier, resultat.png, resultat.largeur, resultat.hauteur)
            }.fold(
                onSuccess = { faite ->
                    _etat.update { it.copy(capture = faite) }
                    null
                },
                onFailure = { texte(Res.string.capture_write_failed, MessageUi.Brut(it.message.orEmpty())) },
            )
        }

    private fun messageEchec(echec: ResultatCapture.Echouee): MessageUi = when (echec.cause) {
        CauseCapture.CONNEXION -> texte(Res.string.capture_failed_connection, MessageUi.Brut(echec.detail))
        CauseCapture.REFUSEE -> texte(Res.string.capture_failed_refused, MessageUi.Brut(echec.detail))
        CauseCapture.ILLISIBLE -> texte(Res.string.capture_failed_unreadable, MessageUi.Brut(echec.detail))
    }

    fun copierCapture() {
        val capture = _etat.value.capture ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { PressePapiers.copierImage(capture.png) } }
                .onSuccess { afficher(texte(Res.string.capture_copied)) }
                .onFailure { afficher(texte(Res.string.capture_write_failed, MessageUi.Brut(it.message.orEmpty()))) }
        }
    }

    fun fermerCapture() = _etat.update { it.copy(capture = null) }

    // --- Video, on the TV ---------------------------------------------------------------------

    fun enregistrer() {
        val visee = cible() ?: return afficher(texte(Res.string.msg_connect_first))
        if (_etat.value.enregistrement != PhaseEnregistrement.Inactif) return
        _etat.update { it.copy(enregistrement = PhaseEnregistrement.Demarrage) }
        viewModelScope.launch {
            // One or the other: the mirror closes when recording starts.
            session?.let { miroir -> withContext(Dispatchers.IO) { miroir.arreter() } }
            when (val demarrage = enregistrement.demarrer()) {
                is Demarrage.Lance -> {
                    enregistre = visee.infos to LocalDateTime.now()
                    _etat.update {
                        it.copy(enregistrement = PhaseEnregistrement.EnCours(System.currentTimeMillis(), demarrage.limiteS))
                    }
                    if (demarrage.limiteS != null) afficher(texte(Res.string.record_limit))
                    guetter()
                    if (_etat.value.fermeture) terminer()
                }

                is Demarrage.Refuse -> {
                    _etat.update { it.copy(enregistrement = PhaseEnregistrement.Inactif) }
                    afficher(messageEchec(demarrage.cause, demarrage.detail))
                    quitterSiDemande()
                }
            }
        }
    }

    /** Checks every two seconds whether the recorder stopped on its own; no answer means try again next time. */
    private fun guetter() {
        veille?.cancel()
        veille = viewModelScope.launch {
            while (isActive) {
                delay(VEILLE_MS)
                if (enregistrement.vivant() == false) {
                    terminer()
                    return@launch
                }
            }
        }
    }

    fun arreterEnregistrement() = terminer()

    /** Stops the recorder, copies the video to `Videos\TV Slim`, then deletes it from the TV. */
    private fun terminer() {
        if (_etat.value.enregistrement !is PhaseEnregistrement.EnCours || fin?.isActive == true) return
        veille?.cancel()
        _etat.update { it.copy(enregistrement = PhaseEnregistrement.Arret) }
        fin = viewModelScope.launch {
            when (val arret = enregistrement.arreter()) {
                is Arret.Termine -> copier(arret.taille)
                is Arret.Refuse -> afficher(messageEchec(arret.cause, arret.detail))
            }
            _etat.update { it.copy(enregistrement = PhaseEnregistrement.Inactif) }
            quitterSiDemande()
        }
    }

    private suspend fun copier(taille: Long) {
        val (infos, debut) = enregistre ?: (InfosAppareil.VIDE to LocalDateTime.now())
        _etat.update { it.copy(enregistrement = PhaseEnregistrement.Copie(0f)) }
        val issue = withContext(Dispatchers.IO) {
            runCatching {
                val dossier = File(dossierVideos(), SOUS_DOSSIER).apply { mkdirs() }
                val fichier = File(dossier, CaptureEcran.nomFichier(infos, debut, "mp4"))
                val partiel = File(dossier, fichier.name + ".partiel")
                var palier = -1
                val resultat = partiel.outputStream().buffered().use { flux ->
                    enregistrement.rapatrier(flux, taille, surRecu = { recu ->
                        // Update once per percent, not thousands of recompositions per copy.
                        val pourcent = (recu * 100 / taille.coerceAtLeast(1)).toInt()
                        if (pourcent != palier) {
                            palier = pourcent
                            _etat.update { it.copy(enregistrement = PhaseEnregistrement.Copie(recu.toFloat() / taille)) }
                        }
                    })
                }
                if (!resultat.reussi) {
                    partiel.delete()
                    error(resultat.sortie)
                }
                Files.move(partiel.toPath(), fichier.toPath(), StandardCopyOption.REPLACE_EXISTING)
                fichier
            }
        }
        issue.onSuccess { fichier ->
            enregistrement.nettoyer()
            if (!_etat.value.fermeture) _etat.update { it.copy(video = fichier) }
        }.onFailure { erreur ->
            Traces.avertir(TAG, "Vidéo non copiée", erreur)
            // The video stays on the TV; the next recording replaces it.
            afficher(texte(Res.string.record_copy_failed, MessageUi.Brut(erreur.message.orEmpty()), EnregistrementTv.VIDEO))
        }
    }

    private fun messageEchec(cause: CauseEnregistrement, detail: String): MessageUi = when (cause) {
        CauseEnregistrement.CONNEXION -> texte(Res.string.capture_failed_connection, MessageUi.Brut(detail))
        CauseEnregistrement.INDISPONIBLE -> texte(Res.string.record_unavailable, MessageUi.Brut(detail))
        CauseEnregistrement.ECHEC -> texte(Res.string.record_failed_start, MessageUi.Brut(detail))
        CauseEnregistrement.VIDE -> texte(Res.string.record_empty, MessageUi.Brut(detail))
    }

    fun fermerVideo() = _etat.update { it.copy(video = null) }

    // --- Mirror, via scrcpy ---------------------------------------------------------------------

    /** Opens the mirror, offering to download scrcpy first if it is missing. */
    fun ouvrirMiroir(titre: String) {
        if (cible() == null) return afficher(texte(Res.string.msg_connect_first))
        if (_etat.value.scrcpy != PhaseScrcpy.Inactif || _etat.value.enregistrement != PhaseEnregistrement.Inactif) return
        viewModelScope.launch {
            val exe = withContext(Dispatchers.IO) { localisation.trouver() }
            if (exe == null) {
                _etat.update { it.copy(telechargementPropose = titre) }
            } else {
                lancer(exe, titre)
            }
        }
    }

    fun refuserTelechargement() = _etat.update { it.copy(telechargementPropose = null) }

    fun accepterTelechargement() {
        val titre = _etat.value.telechargementPropose ?: return
        if (_etat.value.scrcpy != PhaseScrcpy.Inactif) return
        _etat.update { it.copy(scrcpy = PhaseScrcpy.Telechargement(0f)) }
        viewModelScope.launch {
            runCatching {
                installation.installer { avancee -> _etat.update { it.copy(scrcpy = PhaseScrcpy.Telechargement(avancee)) } }
            }.onSuccess { exe ->
                _etat.update { it.copy(scrcpy = PhaseScrcpy.Inactif, telechargementPropose = null) }
                lancer(exe, titre)
            }.onFailure { erreur ->
                Traces.avertir(TAG, "scrcpy non téléchargé", erreur)
                _etat.update { it.copy(scrcpy = PhaseScrcpy.Inactif, telechargementPropose = null) }
                afficher(
                    if (erreur is EmpreinteInattendue) {
                        texte(Res.string.scrcpy_fingerprint_failed)
                    } else {
                        texte(Res.string.scrcpy_download_failed, MessageUi.Brut(erreur.message.orEmpty()))
                    },
                )
            }
        }
    }

    private suspend fun lancer(exe: File, titre: String) {
        val visee = cible() ?: return afficher(texte(Res.string.msg_connect_first))
        val lancee = withContext(Dispatchers.IO) {
            runCatching { SessionScrcpy.lancer(exe, ArgumentsScrcpy.miroir(visee.hote, visee.port, titre)) }
        }.getOrElse { erreur ->
            Traces.avertir(TAG, "scrcpy non lancé", erreur)
            return afficher(texte(Res.string.scrcpy_failed, MessageUi.Brut(erreur.message.orEmpty())))
        }
        session = lancee
        _etat.update { it.copy(scrcpy = PhaseScrcpy.Actif) }
        viewModelScope.launch {
            val code = lancee.attendre()
            if (session === lancee) session = null
            _etat.update { it.copy(scrcpy = PhaseScrcpy.Inactif) }
            if (code != 0) afficher(echecScrcpy(lancee.sortie))
        }
    }

    private fun echecScrcpy(sortie: List<String>): MessageUi {
        if (sortie.any { it.contains("unauthorized", ignoreCase = true) }) return texte(Res.string.scrcpy_unauthorized)
        val motif = sortie.lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: sortie.lastOrNull { it.isNotBlank() }.orEmpty()
        return texte(Res.string.scrcpy_failed, MessageUi.Brut(motif))
    }

    /** Closes the scrcpy window the way a mouse click would. */
    fun arreterMiroir() {
        val active = session ?: return
        viewModelScope.launch(Dispatchers.IO) { active.arreter() }
    }

    // --- Closing TV Slim ------------------------------------------------------------------------

    /**
     * Closes the mirror, then calls [quitter], right away or once the current video has reached the PC rather
     * than leaving it on the TV with the recorder still running.
     */
    fun fermer(quitter: () -> Unit) {
        session?.arreter()
        if (_etat.value.enregistrement == PhaseEnregistrement.Inactif) return quitter()
        this.quitter = quitter
        _etat.update { it.copy(fermeture = true) }
        terminer()
    }

    private fun quitterSiDemande() {
        if (_etat.value.fermeture) quitter?.invoke()
    }

    fun effacerMessage() = _etat.update { it.copy(message = null) }

    private fun afficher(message: MessageUi) = _etat.update { it.copy(message = message) }

    private companion object {
        const val TAG = "Ecran"
        const val SOUS_DOSSIER = "TV Slim"
        const val VEILLE_MS = 2_000L
    }
}
