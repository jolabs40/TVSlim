package net.jolabs40.tvslim.windows.adb

import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import net.jolabs40.tvslim.windows.outils.Traces
import net.jolabs40.tvslim.windows.outils.detail
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

enum class EtatConnexion { DECONNECTE, CONNEXION, CONNECTE, ERREUR }

/** Pourquoi la connexion n'a pas abouti : l'écran le dit, dans la langue de la personne. */
enum class ProblemeConnexion { REFUSEE, DELAI, NON_AUTORISEE, INJOIGNABLE, AUTRE }

data class ConnexionUi(
    val etat: EtatConnexion = EtatConnexion.DECONNECTE,
    val hote: String = "",
    val port: Int = PORT_ADB_PAR_DEFAUT,
    val probleme: ProblemeConnexion? = null,
    /** Le message technique d'origine, affiché en petit sous l'explication. */
    val detail: String = "",
)

const val PORT_ADB_PAR_DEFAUT = 5555

/**
 * Connexion ADB de l'ordinateur vers un téléviseur, en Kotlin pur (dadb) : ni `adb.exe`, ni
 * serveur ADB, rien à installer — le même client que le compagnon Android.
 *
 * La première connexion fait apparaître sur le téléviseur « Autoriser le débogage depuis cet
 * ordinateur ? ». Une fois acceptée à la télécommande, la clé publique est inscrite dans
 * `/data/misc/adb/adb_keys` : **l'autorisation survit aux redémarrages**.
 *
 * Deux écarts assumés avec le compagnon, tous deux appris en lisant dadb :
 *  - dadb n'ouvre la connexion qu'à la première commande. On la provoque dès [connecter], pour ne
 *    pas afficher « connecté » avant que le téléviseur ait accepté quoi que ce soit ;
 *  - un `withTimeout` n'interrompt pas une lecture de socket bloquée : les délais maximaux sont
 *    tenus par [sousSurveillance], qui ferme la session depuis un autre fil.
 */
class ClientAdb(
    private val depotCles: DepotCles,
) : ExecuteurCommande {

    private val _connexion = MutableStateFlow(ConnexionUi())
    val connexion: StateFlow<ConnexionUi> = _connexion.asStateFlow()

    private val verrou = Mutex()

    @Volatile
    private var session: Dadb? = null

    /** Le dernier téléviseur joint volontairement : c'est vers lui que va toute reprise. */
    private var cible: Pair<String, Int>? = null

    /** Quand la dernière reprise a échoué, pour ne pas la retenter à chaque commande. */
    private var dernierEchecReprise = 0L

    /**
     * Ouvre la connexion et attend que le téléviseur l'accepte. [discret] sert aux tentatives que
     * personne n'a demandées — au retour sur la fenêtre, par exemple : un échec y est banal
     * (téléviseur éteint) et ne mérite pas d'afficher une erreur en travers de l'écran.
     */
    suspend fun connecter(
        hote: String,
        port: Int = PORT_ADB_PAR_DEFAUT,
        discret: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        verrou.withLock {
            fermerSession()
            _connexion.value = ConnexionUi(EtatConnexion.CONNEXION, hote, port)
            val delai = if (discret) DELAI_REPRISE_MS else DELAI_CONNEXION_MS
            when (val issue = ouvrir(hote, port, delai)) {
                is Ouverture.Reussie -> {
                    session = issue.session
                    cible = hote to port
                    dernierEchecReprise = 0L
                    _connexion.value = ConnexionUi(EtatConnexion.CONNECTE, hote, port)
                    true
                }

                is Ouverture.Echouee -> {
                    Traces.avertir(TAG, "Connexion impossible" + detail("$hote:$port"), issue.erreur)
                    _connexion.value = if (discret) {
                        ConnexionUi(EtatConnexion.DECONNECTE, hote, port)
                    } else {
                        ConnexionUi(
                            etat = EtatConnexion.ERREUR,
                            hote = hote,
                            port = port,
                            probleme = issue.probleme,
                            detail = issue.erreur.message.orEmpty(),
                        )
                    }
                    false
                }
            }
        }
    }

    fun deconnecter() {
        // Sans le verrou, exprès : fermer la socket est justement ce qui débloque une commande
        // en attente, et le verrou est tenu pendant ce temps.
        fermerSession()
        // Se déconnecter est un choix : rien ne doit rouvrir la session dans le dos.
        cible = null
        _connexion.value = ConnexionUi()
    }

    /**
     * Un téléviseur qui s'endort ferme sa session sans prévenir, et l'affaire se découvre à la
     * commande suivante. On rouvre une fois et on rejoue.
     *
     * Le rejeu est sans danger parce que **toutes** les commandes envoyées d'ici sont
     * idempotentes : lectures, `pm disable-user`, `pm enable`, `am force-stop`, `settings put`,
     * `pm grant`, ouverture d'une fiche de boutique. Une commande qui ne le serait pas ne devrait
     * pas passer par ce chemin.
     */
    override suspend fun executer(commande: String): ResultatShell = withContext(Dispatchers.IO) {
        verrou.withLock {
            when (val premiere = tenter(commande)) {
                is Issue.Repondu -> premiere.resultat
                is Issue.Rompue ->
                    if (!reprendre()) {
                        signalerRupture(premiere)
                        ResultatShell.indisponible(premiere.motif)
                    } else {
                        when (val seconde = tenter(commande)) {
                            is Issue.Repondu -> seconde.resultat
                            is Issue.Rompue -> {
                                signalerRupture(seconde)
                                ResultatShell.indisponible(seconde.motif)
                            }
                        }
                    }
            }
        }
    }

    private sealed interface Ouverture {
        data class Reussie(val session: Dadb) : Ouverture
        data class Echouee(val erreur: Throwable, val probleme: ProblemeConnexion) : Ouverture
    }

    /** Ce qu'une commande a donné : une réponse, ou une session à rouvrir. */
    private sealed interface Issue {
        data class Repondu(val resultat: ResultatShell) : Issue
        data class Rompue(val motif: String, val probleme: ProblemeConnexion) : Issue
    }

    private suspend fun ouvrir(hote: String, port: Int, delaiMs: Long): Ouverture {
        val ouverte = try {
            // La socket attend jusqu'à DELAI_CONNEXION_MS : le temps qu'on accepte la demande sur
            // le téléviseur. Le délai plus court d'une reprise est tenu par la surveillance.
            Dadb.create(hote, port, depotCles.paire(), DELAI_TCP_MS, DELAI_CONNEXION_MS.toInt())
        } catch (erreur: Exception) {
            return Ouverture.Echouee(erreur, diagnostic(erreur))
        }
        // Un aller-retour anodin force la poignée de main — et donc l'autorisation — maintenant.
        return sousSurveillance(ouverte, delaiMs) { ouverte.shell("echo tvslim") }.fold(
            onSuccess = { Ouverture.Reussie(ouverte) },
            onFailure = { erreur ->
                runCatching { ouverte.close() }
                Ouverture.Echouee(erreur, diagnostic(erreur))
            },
        )
    }

    private suspend fun tenter(commande: String): Issue {
        val active = session ?: return Issue.Rompue(MOTIF_AUCUNE_SESSION, ProblemeConnexion.AUTRE)

        return sousSurveillance(active, DELAI_COMMANDE_MS) { active.shell(commande) }.fold(
            onSuccess = { sortie ->
                Issue.Repondu(
                    ResultatShell(
                        code = sortie.exitCode,
                        sortie = listOf(sortie.output, sortie.errorOutput)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                            .trim(),
                    ),
                )
            },
            onFailure = { erreur ->
                val delaiDepasse = erreur is DelaiDepasse
                Traces.avertir(
                    TAG,
                    (if (delaiDepasse) "Délai dépassé" else "Commande interrompue") + detail(commande),
                    erreur,
                )
                fermerSession()
                if (delaiDepasse) {
                    Issue.Rompue(MOTIF_DELAI, ProblemeConnexion.DELAI)
                } else {
                    Issue.Rompue(
                        erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName,
                        diagnostic(erreur),
                    )
                }
            },
        )
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

        Traces.info(TAG, "Session rompue, reprise" + detail("$hote:$port"))
        _connexion.value = _connexion.value.copy(etat = EtatConnexion.CONNEXION)
        return when (val issue = ouvrir(hote, port, DELAI_REPRISE_MS)) {
            is Ouverture.Reussie -> {
                session = issue.session
                dernierEchecReprise = 0L
                _connexion.value = ConnexionUi(EtatConnexion.CONNECTE, hote, port)
                true
            }

            is Ouverture.Echouee -> {
                dernierEchecReprise = maintenant
                false
            }
        }
    }

    /**
     * Exécute un appel bloquant de dadb avec un **vrai** délai maximal.
     *
     * `withTimeout` n'y suffit pas : il annule la coroutine, pas la lecture de socket en cours,
     * qui continuerait d'attendre — verrou tenu, application figée. Fermer la session depuis un
     * autre fil, si : la lecture lève aussitôt une exception, rendue ici en [DelaiDepasse].
     */
    private suspend fun <T> sousSurveillance(
        active: Dadb,
        delaiMs: Long,
        appel: () -> T,
    ): Result<T> = coroutineScope {
        val depasse = AtomicBoolean(false)
        val chien = launch(Dispatchers.IO) {
            delay(delaiMs)
            depasse.set(true)
            runCatching { active.close() }
        }
        try {
            Result.success(appel())
        } catch (erreur: Exception) {
            Result.failure(if (depasse.get()) DelaiDepasse(erreur) else erreur)
        } finally {
            chien.cancel()
        }
    }

    private class DelaiDepasse(cause: Throwable) : IOException("délai dépassé", cause)

    private fun signalerRupture(issue: Issue.Rompue) {
        _connexion.value = _connexion.value.copy(
            etat = EtatConnexion.ERREUR,
            probleme = issue.probleme,
            detail = issue.motif,
        )
    }

    private fun fermerSession() {
        runCatching { session?.close() }
        session = null
    }

    private fun diagnostic(erreur: Throwable): ProblemeConnexion =
        diagnostiquer(erreur, delaiDepasse = erreur is DelaiDepasse)

    private companion object {
        const val TAG = "Adb"

        /** Ouverture TCP : un téléviseur allumé sur le réseau local répond en quelques millisecondes. */
        const val DELAI_TCP_MS = 5_000

        /** Large : la connexion attend que quelqu'un accepte la demande sur le téléviseur. */
        const val DELAI_CONNEXION_MS = 45_000L

        /**
         * Une commande de gestion de paquets répond en quelques dizaines de millisecondes ;
         * `dumpsys meminfo` peut demander plusieurs secondes sur un petit boîtier.
         */
        const val DELAI_COMMANDE_MS = 30_000L

        /** Court : une reprise ne demande aucune validation, elle aboutit ou l'appareil dort. */
        const val DELAI_REPRISE_MS = 12_000L

        /** Après un échec de reprise, on laisse le téléviseur tranquille un moment. */
        const val REPOS_APRES_ECHEC_MS = 20_000L

        // Ces motifs remontent dans les résultats du moteur et dans le journal, comme ceux du
        // noyau partagé — qui sont en français.
        const val MOTIF_AUCUNE_SESSION = "Aucun téléviseur connecté."
        const val MOTIF_DELAI =
            "Le téléviseur n'a pas répondu à temps. Vérifiez qu'il est allumé et réessayez."
    }
}
