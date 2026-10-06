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

/** Ce qu'on s'apprête à faire d'une application, soumis à confirmation. */
sealed interface ConfirmationApplication {
    val application: ApplicationAppareil

    /** Par le moteur, avec ses garde-fous : [entree] est celle du catalogue, effets de bord compris. */
    data class Desactivation(override val application: ApplicationAppareil, val entree: EntreePaquet) : ConfirmationApplication

    data class Desinstallation(override val application: ApplicationAppareil) : ConfirmationApplication
}

data class EtatApplications(
    val applications: List<ApplicationAppareil> = emptyList(),
    /** Une lecture a abouti : l'onglet n'en relance pas une de lui-même. */
    val lue: Boolean = false,
    val chargement: Boolean = false,
    /** Noms et icônes lus, sur le nombre à lire ; `null` hors de cette étape. */
    val avancee: Pair<Int, Int>? = null,
    val recherche: String = "",
    val confirmation: ConfirmationApplication? = null,
    /** Le paquet dont une action est en cours : ses boutons attendent. */
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
 * L'onglet Applications : les applications du menu et celles que la personne a installées, avec leur nom et leur
 * icône (`LecteurApplications`, dans le noyau), et ce qu'on en fait.
 *
 * Désactiver passe par le moteur et ses garde-fous, avec les règles de l'onglet Paquets : sur un téléviseur, seulement
 * ce que le catalogue décrit ; sur un téléphone, aussi ses applications du menu. Réactiver est toujours permis.
 * Désinstaller ne vaut que pour une application installée par la personne, et ne s'annule pas.
 */
class PiloteApplications(
    private val client: ClientAdb,
    private val cache: CacheApplications,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    private val etatApp: () -> EtatApp,
    private val moteur: () -> MoteurDebloat?,
    journal: () -> JournalRepository?,
    /** L'onglet Paquets se relit après une désactivation faite d'ici. */
    private val rafraichirPaquets: () -> Unit,
    private val remercier: () -> Unit,
) {

    private val _etat = MutableStateFlow(EtatApplications())
    val etat: StateFlow<EtatApplications> = _etat.asStateFlow()

    private val actions = ActionsApplications(client, journal)

    init {
        // Ce qu'on a lu appartient à l'appareil : se déconnecter, ou en joindre un autre, l'oublie.
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
     * La même lecture, lancée d'avance à la connexion par la seconde session : rien si l'onglet a déjà lu ou lit, et
     * un échec ne se dit pas — personne n'a rien demandé, l'onglet relira de lui-même.
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
            // Interrompue par une déconnexion aussi : l'onglet ne doit pas attendre une lecture qui ne viendra plus.
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

    /** L'entrée du catalogue qui permet de la désactiver, si elle y est et n'est pas protégée. */
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

    /** Une action à la fois par application ; vrai rendu par [bloc], la liste et l'onglet Paquets se relisent. */
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
