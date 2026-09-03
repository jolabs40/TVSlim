package net.jolabs40.tvslim.remote.adb

import android.util.Log
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import javax.inject.Inject
import javax.inject.Singleton

enum class EtatConnexion { DECONNECTE, CONNEXION, CONNECTE, ERREUR }

data class ConnexionUi(
    val etat: EtatConnexion = EtatConnexion.DECONNECTE,
    val hote: String = "",
    val port: Int = PORT_ADB_PAR_DEFAUT,
    val message: String = "",
)

const val PORT_ADB_PAR_DEFAUT = 5555

/**
 * Connexion ADB du téléphone vers un téléviseur, en Kotlin pur (dadb) : ni binaire `adb`, ni
 * serveur ADB, ni ordinateur.
 *
 * La première connexion fait apparaître sur le téléviseur la demande « Autoriser le débogage
 * depuis cet appareil ? ». Une fois acceptée à la télécommande, la clé publique est inscrite
 * dans `/data/misc/adb/adb_keys` du téléviseur : **l'autorisation survit aux redémarrages**.
 * C'est précisément ce qui manque à un service privilégié local, qui meurt à chaque extinction.
 *
 * La clé privée ne quitte jamais l'appareil, et y dort chiffrée (voir [DepotCles]).
 */
@Singleton
class ClientAdb @Inject constructor(
    private val depotCles: DepotCles,
) : ExecuteurCommande {

    private val _connexion = MutableStateFlow(ConnexionUi())
    val connexion: StateFlow<ConnexionUi> = _connexion.asStateFlow()

    private val verrou = Mutex()
    private var session: Dadb? = null

    /** Le dernier téléviseur joint volontairement : c'est vers lui que va toute reprise. */
    private var cible: Pair<String, Int>? = null

    /** Quand la dernière reprise a échoué, pour ne pas la retenter à chaque commande. */
    private var dernierEchecReprise = 0L

    /**
     * Ouvre la connexion. [discret] sert aux tentatives que personne n'a demandées — au retour
     * dans l'application, par exemple : un échec y est banal (téléviseur éteint) et ne mérite
     * pas d'afficher une erreur en travers de l'écran.
     */
    suspend fun connecter(
        hote: String,
        port: Int = PORT_ADB_PAR_DEFAUT,
        discret: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        verrou.withLock {
            fermerSession()
            _connexion.value = ConnexionUi(EtatConnexion.CONNEXION, hote, port)
            try {
                val delai = if (discret) DELAI_REPRISE_MS else DELAI_CONNEXION_MS
                val ouverte = withTimeoutOrNull(delai) {
                    Dadb.create(hote, port, depotCles.paire())
                } ?: throw java.net.SocketTimeoutException(MESSAGE_ATTENTE_AUTORISATION)
                session = ouverte
                cible = hote to port
                dernierEchecReprise = 0L
                _connexion.value = ConnexionUi(EtatConnexion.CONNECTE, hote, port)
                true
            } catch (erreur: Throwable) {
                Log.w(TAG, "Connexion à $hote:$port impossible", erreur)
                _connexion.value = if (discret) {
                    ConnexionUi(EtatConnexion.DECONNECTE, hote, port)
                } else {
                    ConnexionUi(EtatConnexion.ERREUR, hote, port, diagnostic(erreur))
                }
                false
            }
        }
    }

    fun deconnecter() {
        fermerSession()
        // Se déconnecter est un choix : rien ne doit rouvrir la session dans le dos.
        cible = null
        _connexion.value = ConnexionUi()
    }

    /**
     * Un téléviseur qui s'endort ferme sa session sans prévenir, et l'affaire se découvre à la
     * commande suivante. Plutôt que de renvoyer la personne sur « Se connecter », on rouvre
     * une fois et on rejoue.
     *
     * Le rejeu est sans danger parce que **toutes** les commandes envoyées d'ici sont
     * idempotentes : lectures, `pm disable-user`, `pm enable`, `am force-stop`, `settings put`,
     * ouverture d'une fiche de boutique. Rien qui compte, ajoute ou supprime. Une commande qui
     * ne le serait pas ne devrait pas passer par ce chemin.
     */
    override suspend fun executer(commande: String): ResultatShell = withContext(Dispatchers.IO) {
        verrou.withLock {
            when (val premiere = tenter(commande)) {
                is Issue.Repondu -> premiere.resultat
                is Issue.Rompue ->
                    if (!reprendre()) {
                        signalerRupture(premiere.motif)
                        ResultatShell.indisponible(premiere.motif)
                    } else {
                        when (val seconde = tenter(commande)) {
                            is Issue.Repondu -> seconde.resultat
                            is Issue.Rompue -> {
                                signalerRupture(seconde.motif)
                                ResultatShell.indisponible(seconde.motif)
                            }
                        }
                    }
            }
        }
    }

    /** Ce qu'une commande a donné : une réponse, ou une session à rouvrir. */
    private sealed interface Issue {
        data class Repondu(val resultat: ResultatShell) : Issue
        data class Rompue(val motif: String) : Issue
    }

    private suspend fun tenter(commande: String): Issue {
        val active = session ?: return Issue.Rompue("Aucun téléviseur connecté.")

        // Sans délai maximal, un téléviseur qui se fige ou s'endort en pleine commande
        // bloquerait l'application pour toujours — et le verrou avec elle. Fermer la
        // session est ce qui débloque réellement la lecture en cours.
        val reponse = withTimeoutOrNull(DELAI_COMMANDE_MS) {
            runCatching { active.shell(commande) }
        }

        return when {
            reponse == null -> {
                Log.w(TAG, "Délai dépassé : $commande")
                fermerSession()
                Issue.Rompue(MESSAGE_DELAI)
            }

            reponse.isFailure -> {
                val erreur = reponse.exceptionOrNull() ?: IllegalStateException()
                Log.w(TAG, "Commande refusée : $commande", erreur)
                fermerSession()
                Issue.Rompue(diagnostic(erreur))
            }

            else -> reponse.getOrThrow().let { sortie ->
                Issue.Repondu(
                    ResultatShell(
                        code = sortie.exitCode,
                        sortie = listOf(sortie.output, sortie.errorOutput)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                            .trim(),
                    ),
                )
            }
        }
    }

    /**
     * Rouvre la session sur le même téléviseur, sans rien demander à personne : la clé est déjà
     * autorisée, il n'y a pas de dialogue à valider à la télécommande.
     *
     * Un échec met la reprise au repos un moment. Sans cela, une désactivation de quatre-vingts
     * paquets sur un téléviseur qu'on vient d'éteindre tenterait quatre-vingts reconnexions.
     */
    private suspend fun reprendre(): Boolean {
        val (hote, port) = cible ?: return false
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierEchecReprise < REPOS_APRES_ECHEC_MS) return false

        Log.i(TAG, "Session rompue, reprise sur $hote:$port")
        _connexion.value = _connexion.value.copy(etat = EtatConnexion.CONNEXION)
        val ouverte = withTimeoutOrNull(DELAI_REPRISE_MS) {
            runCatching { Dadb.create(hote, port, depotCles.paire()) }.getOrNull()
        }
        if (ouverte == null) {
            dernierEchecReprise = maintenant
            return false
        }
        session = ouverte
        dernierEchecReprise = 0L
        _connexion.value = ConnexionUi(EtatConnexion.CONNECTE, hote, port)
        return true
    }

    private fun signalerRupture(message: String) {
        _connexion.value = _connexion.value.copy(etat = EtatConnexion.ERREUR, message = message)
    }

    private fun fermerSession() {
        runCatching { session?.close() }
        session = null
    }

    /**
     * Traduit les échecs les plus courants. Le premier est le plus fréquent : la demande
     * d'autorisation attend sur le téléviseur, personne ne l'a validée.
     */
    private fun diagnostic(erreur: Throwable): String {
        val texte = erreur.message.orEmpty()
        return when {
            texte.contains("Connection refused", ignoreCase = true) ->
                "Connexion refusée : le débogage ADB réseau est-il activé sur le téléviseur ?"

            texte.contains("timed out", ignoreCase = true) ||
                texte.contains("timeout", ignoreCase = true) ->
                "Délai dépassé. Si le téléviseur affiche une demande d'autorisation, acceptez-la " +
                    "à la télécommande, puis réessayez."

            texte.contains("unauthorized", ignoreCase = true) ->
                "Autorisation refusée par le téléviseur. Acceptez la demande de débogage, en " +
                    "cochant « Toujours autoriser »."

            texte.isBlank() -> erreur.javaClass.simpleName
            else -> texte
        }
    }

    private companion object {
        const val TAG = "TVSlim/Adb"

        /** Large : la connexion attend que quelqu'un accepte la demande sur le téléviseur. */
        const val DELAI_CONNEXION_MS = 45_000L

        /** Une commande de gestion de paquets répond en quelques dizaines de millisecondes. */
        const val DELAI_COMMANDE_MS = 20_000L

        /** Court : une reprise ne demande aucune validation, elle aboutit ou l'appareil dort. */
        const val DELAI_REPRISE_MS = 12_000L

        /** Après un échec de reprise, on laisse le téléviseur tranquille un moment. */
        const val REPOS_APRES_ECHEC_MS = 20_000L

        const val MESSAGE_DELAI =
            "Le téléviseur n'a pas répondu à temps. Vérifiez qu'il est allumé et réessayez."

        const val MESSAGE_ATTENTE_AUTORISATION =
            "timed out: la demande d'autorisation attend peut-être sur le téléviseur."
    }
}
