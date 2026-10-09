package net.jolabs40.tvslim.windows.ui

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
import net.jolabs40.tvslim.applications.CacheApplications
import net.jolabs40.tvslim.applications.CauseLecture
import net.jolabs40.tvslim.applications.LecteurApplications
import net.jolabs40.tvslim.applications.ResultatLecture
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.soutien.InvitationSoutien
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.apps_disabled_done
import net.jolabs40.tvslim.windows.ressources.apps_enabled_done
import net.jolabs40.tvslim.windows.ressources.apps_failed
import net.jolabs40.tvslim.windows.ressources.apps_opened
import net.jolabs40.tvslim.windows.ressources.apps_read_connection
import net.jolabs40.tvslim.windows.ressources.apps_read_helper_missing
import net.jolabs40.tvslim.windows.ressources.apps_read_helper_refused
import net.jolabs40.tvslim.windows.ressources.apps_read_send
import net.jolabs40.tvslim.windows.ressources.apps_stopped
import net.jolabs40.tvslim.windows.ressources.apps_uninstalled
import net.jolabs40.tvslim.windows.ressources.msg_connect_first

/** An app action waiting for confirmation. */
sealed interface ConfirmationApplication {
    val application: ApplicationAppareil

    /** Goes through the engine and its safeguards; [entree] is the catalogue entry, side effects included. */
    data class Desactivation(override val application: ApplicationAppareil, val entree: EntreePaquet) : ConfirmationApplication

    data class Desinstallation(override val application: ApplicationAppareil) : ConfirmationApplication
}

data class EtatApplications(
    val applications: List<ApplicationAppareil> = emptyList(),
    /** Set after a successful read, so the tab does not start another on its own. */
    val lue: Boolean = false,
    val chargement: Boolean = false,
    /** Names and icons read so far, out of the total; `null` outside that step. */
    val avancee: Pair<Int, Int>? = null,
    val recherche: String = "",
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
 * Applications tab: launcher apps and user-installed apps, with name and icon (`LecteurApplications`, in the
 * core), and the actions on them.
 *
 * Disabling goes through the engine and its safeguards, with the Packages tab rules: on a TV only what the
 * catalogue describes, on a phone its launcher apps too. Re-enabling is always allowed. Uninstalling is limited to
 * user-installed apps and cannot be undone.
 */
class PiloteApplications(
    private val client: ClientAdb,
    private val cache: CacheApplications,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    private val etatApp: () -> EtatApp,
    private val moteur: () -> MoteurDebloat?,
    journal: () -> JournalRepository?,
    /** Re-reads the Packages tab after a disable done from here. */
    private val rafraichirPaquets: () -> Unit,
    private val remercier: () -> Unit,
) {

    private val _etat = MutableStateFlow(EtatApplications())
    val etat: StateFlow<EtatApplications> = _etat.asStateFlow()

    private val actions = ActionsApplications(client, journal)

    init {
        // What was read belongs to the device: forget it on disconnect or when switching devices.
        portee.launch {
            client.connexion
                .map { if (it.etat == EtatConnexion.DECONNECTE) "" else it.hote }
                .distinctUntilChanged()
                .drop(1)
                .collect { _etat.value = EtatApplications() }
        }
    }

    fun charger() {
        if (!etatApp().connecte) return afficher(texte(Res.string.msg_connect_first))
        if (_etat.value.chargement) return
        _etat.update { it.copy(chargement = true, avancee = null) }
        portee.launch { lire(client, signaler = true) }
    }

    /**
     * Same read, prefetched on connection over the second session. Skipped if the tab has read or is reading.
     * Failures stay silent: nobody asked, and the tab will read again on its own.
     */
    suspend fun precharger(seconde: ClientAdb) {
        if (_etat.value.lue || _etat.value.chargement) return
        _etat.update { it.copy(chargement = true, avancee = null) }
        lire(seconde, signaler = false)
    }

    private suspend fun lire(session: ClientAdb, signaler: Boolean) {
        try {
            val lecteur = LecteurApplications(session, session, ::aide, cache)
            val resultat = lecteur.lire { applications, fait, total ->
                _etat.update { it.copy(applications = applications, avancee = if (fait < total) fait to total else null) }
            }
            when (resultat) {
                is ResultatLecture.Lues -> _etat.update { it.copy(applications = resultat.applications, lue = true) }
                is ResultatLecture.Echec -> if (signaler) afficher(messageLecture(resultat))
            }
        } finally {
            // Also when a disconnect interrupts it: the tab must not wait for a read that will never finish.
            _etat.update { it.copy(chargement = false, avancee = null) }
        }
    }

    private fun aide() = javaClass.classLoader.getResourceAsStream(LecteurApplications.CHEMIN_RESSOURCE)

    private fun messageLecture(echec: ResultatLecture.Echec): MessageUi = when (echec.cause) {
        CauseLecture.AIDE_ABSENTE -> texte(Res.string.apps_read_helper_missing)
        CauseLecture.ENVOI -> texte(Res.string.apps_read_send, MessageUi.Brut(echec.detail))
        CauseLecture.AIDE_REFUSEE -> texte(Res.string.apps_read_helper_refused, MessageUi.Brut(echec.detail))
        CauseLecture.CONNEXION -> texte(Res.string.apps_read_connection, MessageUi.Brut(echec.detail))
    }

    fun majRecherche(valeur: String) = _etat.update { it.copy(recherche = valeur) }

    /** Catalogue entry that allows disabling the app, if there is one and it is not protected. */
    fun entreeDesactivable(application: ApplicationAppareil): EntreePaquet? {
        val catalogue = etatApp().catalogue
        if (catalogue.estProtege(application.paquet)) return null
        return catalogue.entrees.firstOrNull { it.paquet == application.paquet }
    }

    fun ouvrir(application: ApplicationAppareil) = agir(application) {
        val resultat = actions.ouvrir(application)
        afficher(if (resultat.reussi) texte(Res.string.apps_opened, application.nom) else echec(resultat))
        false
    }

    fun forcerArret(application: ApplicationAppareil) = agir(application) {
        val resultat = moteur()?.forcerArret(application.paquet) ?: return@agir false
        afficher(if (resultat.reussi) texte(Res.string.apps_stopped, application.nom) else echec(resultat))
        false
    }

    fun demanderDesactivation(application: ApplicationAppareil) {
        val entree = entreeDesactivable(application) ?: return
        _etat.update { it.copy(confirmation = ConfirmationApplication.Desactivation(application, entree)) }
    }

    fun reactiver(application: ApplicationAppareil) = agir(application) {
        val resultat = moteur()?.reactiver(listOf(application.paquet))?.singleOrNull() ?: return@agir false
        afficher(if (resultat.reussi) texte(Res.string.apps_enabled_done, application.nom) else echec(resultat))
        resultat.reussi
    }

    fun demanderDesinstallation(application: ApplicationAppareil) {
        if (application.systeme) return
        _etat.update { it.copy(confirmation = ConfirmationApplication.Desinstallation(application)) }
    }

    fun annulerConfirmation() = _etat.update { it.copy(confirmation = null) }

    fun confirmer() {
        val demande = _etat.value.confirmation ?: return
        annulerConfirmation()
        when (demande) {
            is ConfirmationApplication.Desactivation -> agir(demande.application) {
                val courant = etatApp()
                val moteurActif = moteur() ?: return@agir false
                val resultats = moteurActif.desactiver(
                    entrees = listOf(demande.entree),
                    catalogue = courant.catalogue,
                    etats = courant.lignes.associate { it.entree.paquet to it.etat },
                    launchersDisponibles = courant.infos.launchersTiers.isNotEmpty(),
                )
                val resultat = resultats.single()
                afficher(if (resultat.reussi) texte(Res.string.apps_disabled_done, demande.application.nom) else echec(resultat))
                if (InvitationSoutien.merite(resultats)) remercier()
                resultat.reussi
            }

            is ConfirmationApplication.Desinstallation -> agir(demande.application) {
                val resultat = actions.desinstaller(demande.application)
                afficher(if (resultat.reussi) texte(Res.string.apps_uninstalled, demande.application.nom) else echec(resultat))
                resultat.reussi
            }
        }
    }

    /** One action at a time. When [bloc] returns true, the list and the Packages tab are re-read. */
    private fun agir(application: ApplicationAppareil, bloc: suspend () -> Boolean) {
        if (_etat.value.occupee != null) return
        _etat.update { it.copy(occupee = application.paquet) }
        portee.launch {
            val change = runCatching { bloc() }.getOrDefault(false)
            _etat.update { it.copy(occupee = null) }
            if (change) {
                rafraichirPaquets()
                charger()
            }
        }
    }

    private fun echec(resultat: ResultatAction): MessageUi = texte(Res.string.apps_failed, resultat.texte())
}
