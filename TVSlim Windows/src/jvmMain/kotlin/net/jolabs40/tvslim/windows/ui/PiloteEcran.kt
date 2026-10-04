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

/** Le téléviseur visé : son adresse pour scrcpy, ses infos pour nommer les fichiers. */
data class CibleEcran(val hote: String, val port: Int, val infos: InfosAppareil)

/** Une capture enregistrée, montrée en aperçu. */
class CaptureFaite(val fichier: File, val png: ByteArray, val largeur: Int, val hauteur: Int)

sealed interface PhaseScrcpy {
    data object Inactif : PhaseScrcpy

    /** scrcpy n'était pas installé : il arrive de GitHub. */
    data class Telechargement(val progression: Float) : PhaseScrcpy

    /** Lancé : sa fenêtre s'ouvre, ou est ouverte. */
    data object Actif : PhaseScrcpy
}

sealed interface PhaseEnregistrement {
    data object Inactif : PhaseEnregistrement

    data object Demarrage : PhaseEnregistrement

    /** [limiteS] : la durée maximale d'un Android d'avant la 14, au-delà de laquelle il s'arrête seul. */
    data class EnCours(val debut: Long, val limiteS: Int?) : PhaseEnregistrement

    /** L'enregistreur s'arrête et finit d'écrire la vidéo sur le téléviseur. */
    data object Arret : PhaseEnregistrement

    data class Copie(val progression: Float) : PhaseEnregistrement
}

data class EtatEcran(
    val captureEnCours: Boolean = false,
    val capture: CaptureFaite? = null,
    val scrcpy: PhaseScrcpy = PhaseScrcpy.Inactif,
    /** Le titre du miroir qu'on voulait ouvrir quand il a fallu proposer de télécharger scrcpy. */
    val telechargementPropose: String? = null,
    val enregistrement: PhaseEnregistrement = PhaseEnregistrement.Inactif,
    /** La vidéo qu'un enregistrement vient de copier sur le PC. */
    val video: File? = null,
    /** TV Slim se ferme dès que la vidéo en cours est arrivée. */
    val fermeture: Boolean = false,
    val message: MessageUi? = null,
)

/**
 * L'écran du téléviseur depuis le PC :
 *
 * - la **capture**, par la session ADB de TV Slim ;
 * - la **vidéo**, enregistrée sur le téléviseur par son propre `screenrecord`, puis copiée par la même session
 *   (`EnregistrementTv`) — sans son ;
 * - le **miroir**, par scrcpy, qui ouvre sa propre fenêtre et sa propre session, avec sa propre clé : le
 *   téléviseur demande une fois de l'autoriser. TV Slim ne lui prête pas la sienne, qui ne sort jamais déchiffrée.
 *
 * Enregistrer coupe le miroir : l'un ou l'autre, jamais les deux.
 */
class PiloteEcran(
    private val lecteur: LecteurBinaire,
    private val enregistrement: EnregistrementTv,
    private val localisation: LocalisationScrcpy,
    private val installation: InstallationScrcpy,
    /** `null` tant qu'aucun téléviseur n'est joint. */
    private val cible: () -> CibleEcran?,
    private val dossierImages: () -> File,
    private val dossierVideos: () -> File,
) : ViewModel() {

    private val _etat = MutableStateFlow(EtatEcran())
    val etat: StateFlow<EtatEcran> = _etat.asStateFlow()

    private var session: SessionScrcpy? = null

    /** Guette la fin de l'enregistreur : limite atteinte, ou arrêt imprévu. */
    private var veille: Job? = null
    private var fin: Job? = null

    /** Ce qu'on sait du téléviseur au moment d'enregistrer : la vidéo porte son nom et l'heure du début. */
    private var enregistre: Pair<InfosAppareil, LocalDateTime>? = null

    private var quitter: (() -> Unit)? = null

    // --- Capture ----------------------------------------------------------------------------

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

    /** Écrit la capture dans `Images\TV Slim` ; rend un message seulement si l'écriture échoue. */
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

    // --- Vidéo, sur le téléviseur -------------------------------------------------------------

    fun enregistrer() {
        val visee = cible() ?: return afficher(texte(Res.string.msg_connect_first))
        if (_etat.value.enregistrement != PhaseEnregistrement.Inactif) return
        _etat.update { it.copy(enregistrement = PhaseEnregistrement.Demarrage) }
        viewModelScope.launch {
            // L'un ou l'autre : le miroir se ferme quand l'enregistrement commence.
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

    /** Toutes les deux secondes : l'enregistreur s'est-il arrêté seul ? Sans réponse, on attend la suivante. */
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

    /** Arrête l'enregistreur, copie la vidéo dans `Vidéos\TV Slim`, puis l'efface du téléviseur. */
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
                        // Un palier par pour-cent : pas mille recompositions pour une seule copie.
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
            // La vidéo reste sur le téléviseur : le prochain enregistrement la remplacera.
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

    // --- Miroir, par scrcpy ---------------------------------------------------------------------

    /** Ouvre le miroir ; s'il manque scrcpy, propose d'abord de le télécharger. */
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

    /** Ferme la fenêtre de scrcpy comme on la fermerait à la souris. */
    fun arreterMiroir() {
        val active = session ?: return
        viewModelScope.launch(Dispatchers.IO) { active.arreter() }
    }

    // --- Fermeture de TV Slim -------------------------------------------------------------------

    /**
     * Ferme le miroir, puis [quitter] — tout de suite, ou une fois la vidéo en cours arrivée sur le PC : la laisser
     * sur le téléviseur, enregistreur tournant, ne servirait personne.
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
