package net.jolabs40.tvslim.remote.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.applications.ActionsApplications
import net.jolabs40.tvslim.applications.ApplicationAppareil
import net.jolabs40.tvslim.applications.CacheApplicationsFichiers
import net.jolabs40.tvslim.applications.CauseLecture
import net.jolabs40.tvslim.applications.LecteurApplications
import net.jolabs40.tvslim.applications.ResultatLecture
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.ClientAdb
import net.jolabs40.tvslim.remote.adb.EtatConnexion
import net.jolabs40.tvslim.soutien.InvitationSoutien
import java.io.File

/** An app action awaiting confirmation. */
sealed interface ConfirmationApplication {
    val application: ApplicationAppareil

    /** Goes through the engine and its safeguards; [entree] is the catalogue entry, side effects included. */
    data class Desactivation(override val application: ApplicationAppareil, val entree: EntreePaquet) : ConfirmationApplication

    data class Desinstallation(override val application: ApplicationAppareil) : ConfirmationApplication
}

data class EtatApplications(
    val applications: List<ApplicationAppareil> = emptyList(),
    /** A read succeeded; the tab does not start another one by itself. */
    val lue: Boolean = false,
    val chargement: Boolean = false,
    /** Names and icons read so far, out of the total; null outside that step. */
    val avancee: Pair<Int, Int>? = null,
    val recherche: String = "",
    /** App whose action sheet is open. */
    val choisie: ApplicationAppareil? = null,
    val confirmation: ConfirmationApplication? = null,
    /** Package with an action in progress; its buttons are disabled. */
    val occupee: String? = null,
) {
    val affichees: List<ApplicationAppareil> by lazy {
        if (recherche.isBlank()) {
            applications
        } else {
            applications.filter { it.nom.contains(recherche, ignoreCase = true) || it.paquet.contains(recherche, ignoreCase = true) }
        }
    }
    val nombreDesactivees: Int by lazy { applications.count { !it.active } }
}

/**
 * Apps tab: launcher apps and user-installed apps with their names and icons (the core's `LecteurApplications`),
 * and their actions. Same rules as the Windows app.
 */
class PiloteApplications(
    private val contexte: Context,
    private val client: ClientAdb,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
    private val etatRemote: () -> EtatRemote,
    private val moteur: () -> MoteurDebloat?,
    journal: () -> JournalRepository?,
    /** Reloads the Packages tab after a disable done here. */
    private val rafraichirPaquets: () -> Unit,
    private val remercier: () -> Unit,
) {

    private val _etat = MutableStateFlow(EtatApplications())
    val etat: StateFlow<EtatApplications> = _etat.asStateFlow()

    private val cache = CacheApplicationsFichiers(File(contexte.cacheDir, "icones"))
    private val actions = ActionsApplications(client, journal)

    init {
        // What was read belongs to that device: forget it on disconnect or when connecting to another one.
        portee.launch {
            client.connexion
                .map { if (it.etat == EtatConnexion.DECONNECTE) "" else it.hote }
                .distinctUntilChanged()
                .drop(1)
                .collect { _etat.value = EtatApplications() }
        }
    }

    fun charger() {
        if (!etatRemote().connecte) return afficher(contexte.getString(R.string.msg_connect_first))
        if (_etat.value.chargement) return
        _etat.update { it.copy(chargement = true, avancee = null) }
        portee.launch { lire(client, signaler = true) }
    }

    /**
     * Same read, prefetched on connect through the second session. Skipped if the tab has read or is reading;
     * failures are silent since the tab will read again on its own.
     */
    suspend fun precharger(seconde: ClientAdb) {
        if (_etat.value.lue || _etat.value.chargement) return
        _etat.update { it.copy(chargement = true, avancee = null) }
        lire(seconde, signaler = false)
    }

    private suspend fun lire(session: ClientAdb, signaler: Boolean) {
        try {
            val lecteur = LecteurApplications(session, session, { runCatching { contexte.assets.open(LecteurApplications.CHEMIN_RESSOURCE) }.getOrNull() }, cache)
            val resultat = lecteur.lire { applications, fait, total ->
                _etat.update { it.copy(applications = applications, avancee = if (fait < total) fait to total else null) }
            }
            when (resultat) {
                is ResultatLecture.Lues -> _etat.update { it.copy(applications = resultat.applications, lue = true) }
                is ResultatLecture.Echec -> if (signaler) afficher(messageLecture(resultat))
            }
        } finally {
            // Also on disconnect, so the tab does not wait for a read that will never finish.
            _etat.update { it.copy(chargement = false, avancee = null) }
        }
    }

    private fun messageLecture(echec: ResultatLecture.Echec): String = when (echec.cause) {
        CauseLecture.AIDE_ABSENTE -> contexte.getString(R.string.apps_read_helper_missing)
        CauseLecture.ENVOI -> contexte.getString(R.string.apps_read_send, echec.detail)
        CauseLecture.AIDE_REFUSEE -> contexte.getString(R.string.apps_read_helper_refused, echec.detail)
        CauseLecture.CONNEXION -> contexte.getString(R.string.apps_read_connection, echec.detail)
    }

    fun majRecherche(valeur: String) = _etat.update { it.copy(recherche = valeur) }

    fun choisir(application: ApplicationAppareil?) = _etat.update { it.copy(choisie = application) }

    /** Returns the catalogue entry that allows disabling the app, if it exists and is not protected. */
    fun entreeDesactivable(application: ApplicationAppareil): EntreePaquet? {
        val catalogue = etatRemote().catalogue
        if (catalogue.estProtege(application.paquet)) return null
        return catalogue.entrees.firstOrNull { it.paquet == application.paquet }
    }

    fun ouvrir(application: ApplicationAppareil) = agir(application) {
        val resultat = actions.ouvrir(application)
        afficher(if (resultat.reussi) contexte.getString(R.string.apps_opened, application.nom) else echec(resultat.texte(contexte)))
        false
    }

    fun forcerArret(application: ApplicationAppareil) = agir(application) {
        val resultat = moteur()?.forcerArret(application.paquet) ?: return@agir false
        afficher(if (resultat.reussi) contexte.getString(R.string.apps_stopped, application.nom) else echec(resultat.texte(contexte)))
        false
    }

    fun demanderDesactivation(application: ApplicationAppareil) {
        val entree = entreeDesactivable(application) ?: return
        _etat.update { it.copy(choisie = null, confirmation = ConfirmationApplication.Desactivation(application, entree)) }
    }

    fun reactiver(application: ApplicationAppareil) = agir(application) {
        val resultat = moteur()?.reactiver(listOf(application.paquet))?.singleOrNull() ?: return@agir false
        afficher(if (resultat.reussi) contexte.getString(R.string.apps_enabled_done, application.nom) else echec(resultat.texte(contexte)))
        resultat.reussi
    }

    fun demanderDesinstallation(application: ApplicationAppareil) {
        if (application.systeme) return
        _etat.update { it.copy(choisie = null, confirmation = ConfirmationApplication.Desinstallation(application)) }
    }

    fun annulerConfirmation() = _etat.update { it.copy(confirmation = null) }

    fun confirmer() {
        val demande = _etat.value.confirmation ?: return
        annulerConfirmation()
        when (demande) {
            is ConfirmationApplication.Desactivation -> agir(demande.application) {
                val courant = etatRemote()
                val moteurActif = moteur() ?: return@agir false
                val resultats = moteurActif.desactiver(
                    entrees = listOf(demande.entree),
                    catalogue = courant.catalogue,
                    etats = courant.lignes.associate { it.entree.paquet to it.etat },
                    launchersDisponibles = courant.infos.launchersTiers.isNotEmpty(),
                )
                val resultat = resultats.single()
                afficher(
                    if (resultat.reussi) contexte.getString(R.string.apps_disabled_done, demande.application.nom) else echec(resultat.texte(contexte)),
                )
                if (InvitationSoutien.merite(resultats)) remercier()
                resultat.reussi
            }

            is ConfirmationApplication.Desinstallation -> agir(demande.application) {
                val resultat = actions.desinstaller(demande.application)
                afficher(
                    if (resultat.reussi) contexte.getString(R.string.apps_uninstalled, demande.application.nom) else echec(resultat.texte(contexte)),
                )
                resultat.reussi
            }
        }
    }

    /** One action at a time. If [bloc] returns true, the list and the Packages tab are reloaded. */
    private fun agir(application: ApplicationAppareil, bloc: suspend () -> Boolean) {
        if (_etat.value.occupee != null) return
        _etat.update { it.copy(occupee = application.paquet, choisie = null) }
        portee.launch {
            val change = runCatching { bloc() }.getOrDefault(false)
            _etat.update { it.copy(occupee = null) }
            if (change) {
                rafraichirPaquets()
                charger()
            }
        }
    }

    private fun echec(motif: String): String = contexte.getString(R.string.apps_failed, motif)
}
