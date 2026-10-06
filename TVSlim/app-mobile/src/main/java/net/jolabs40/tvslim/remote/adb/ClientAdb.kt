package net.jolabs40.tvslim.remote.adb

import android.content.Context
import android.util.Log
import dadb.AdbShellPacket
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dagger.hilt.android.qualifiers.ApplicationContext
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
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ExecuteurDirect
import net.jolabs40.tvslim.shell.InstallateurApk
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.shell.LecteurBinaire
import net.jolabs40.tvslim.shell.ReponseDirecte
import net.jolabs40.tvslim.shell.ResultatShell
import net.jolabs40.tvslim.shell.SortieBinaire
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

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
 * Connexion ADB du téléphone vers un téléviseur, en Kotlin pur (dadb) : ni binaire `adb`, ni
 * serveur ADB, ni ordinateur.
 *
 * La première connexion fait apparaître sur le téléviseur la demande « Autoriser le débogage
 * depuis cet appareil ? ». Une fois acceptée à la télécommande, la clé publique est inscrite
 * dans `/data/misc/adb/adb_keys` du téléviseur : **l'autorisation survit aux redémarrages**.
 * C'est précisément ce qui manque à un service privilégié local, qui meurt à chaque extinction.
 *
 * La clé privée ne quitte jamais l'appareil, et y dort chiffrée (voir [DepotCles]).
 *
 * Deux leçons de dadb, apprises pour la version Windows et reprises ici :
 *  - dadb n'ouvre la connexion qu'à la première commande. On la provoque dès [connecter], pour ne
 *    pas afficher « connecté » avant que le téléviseur ait accepté quoi que ce soit ;
 *  - un `withTimeout` n'interrompt pas une lecture de socket bloquée : les délais maximaux sont
 *    tenus par [sousSurveillance], qui ferme la session depuis une autre coroutine.
 */
@Singleton
class ClientAdb @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val depotCles: DepotCles,
) : ExecuteurCommande, InstallateurApk, ExecuteurDirect, EnvoyeurFichiers, LecteurBinaire {

    private val _connexion = MutableStateFlow(ConnexionUi())
    val connexion: StateFlow<ConnexionUi> = _connexion.asStateFlow()

    private val verrou = Mutex()

    @Volatile
    private var session: Dadb? = null

    /** Le dernier téléviseur joint volontairement : c'est vers lui que va toute reprise. */
    @Volatile
    private var cible: Pair<String, Int>? = null

    /** Quand la dernière reprise a échoué, pour ne pas la retenter à chaque commande. */
    private var dernierEchecReprise = 0L

    /** Faux pour une seconde session ([ouvrirSeconde]) : rompue, elle ne se rouvre pas. */
    private var repriseAutorisee = true

    /**
     * Ouvre la connexion et attend que le téléviseur l'accepte. [discret] sert aux tentatives que
     * personne n'a demandées — au retour dans l'application, par exemple : un échec y est banal
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
                    Log.w(TAG, "Connexion impossible" + detail("$hote:$port"), issue.erreur)
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

    /**
     * Une seconde session vers le même téléviseur, pour les lectures longues lancées d'avance à la connexion —
     * applications, `dumpsys meminfo`, stockage : la session principale reste libre pendant ce temps pour ce que
     * la personne demande. La clé est déjà autorisée, rien ne s'affiche sur le téléviseur.
     *
     * Elle ne se rouvre jamais d'elle-même : une lecture d'avance qui échoue se refait à la demande, par la
     * principale. La fermer ([deconnecter]) revient à qui l'a ouverte — c'est aussi ce qui interrompt une lecture
     * en cours. Null sans téléviseur joint, ou s'il ne répond pas.
     */
    suspend fun ouvrirSeconde(): ClientAdb? {
        val (hote, port) = cible ?: return null
        val seconde = ClientAdb(contexte, depotCles).apply { repriseAutorisee = false }
        var ouverte = false
        try {
            ouverte = seconde.connecter(hote, port, discret = true)
        } finally {
            // Annulée en route, la connexion a pu aboutir quand même : on ne la laisse pas ouverte.
            if (!ouverte) seconde.deconnecter()
        }
        return seconde.takeIf { ouverte }
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
     * commande suivante. Plutôt que de renvoyer la personne sur « Se connecter », on rouvre
     * une fois et on rejoue.
     *
     * Le rejeu est sans danger parce que **toutes** les commandes envoyées d'ici sont
     * idempotentes : lectures, `pm disable-user`, `pm enable`, `am force-stop`, `settings put`,
     * ouverture d'une fiche de boutique. Rien qui compte, ajoute ou supprime. Une commande qui
     * ne le serait pas ne devrait pas passer par ce chemin.
     */
    override suspend fun executer(commande: String): ResultatShell = withContext(Dispatchers.IO) {
        val demande = System.currentTimeMillis()
        verrou.withLock {
            val obtenu = System.currentTimeMillis()
            val resultat = executerSousVerrou(commande)
            // Une lecture de dossier de plus de dix secondes, vue le 2026-10-04, n'a pas pu être reproduite :
            // la prochaine dira si elle attendait le verrou, la réponse, ou une reprise.
            val fin = System.currentTimeMillis()
            if (fin - demande > SEUIL_LENTEUR_MS) {
                Log.w(
                    TAG,
                    "Commande lente : ${fin - demande} ms, dont ${obtenu - demande} ms d'attente du verrou, " +
                        "code ${resultat.code}" + detail(commande.take(80)),
                )
            }
            resultat
        }
    }

    /** Une tentative ; si la session était tombée, une seule reprise et un rejeu. */
    private suspend fun executerSousVerrou(commande: String): ResultatShell =
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

    /**
     * Envoie un APK et l'installe, en flux vers `cmd package install` : rien n'est d'abord copié sur le
     * téléviseur.
     *
     * Hors du chemin d'[executer], exprès : un envoi de plusieurs dizaines de mégaoctets ne se rejoue pas
     * dans le dos de la personne. Une session tombée avant l'envoi est rouverte ; une rupture pendant
     * l'envoi est rapportée. Le délai maximal suit la taille du fichier, et laisse à Android le temps de
     * vérifier l'application.
     */
    override suspend fun installer(apk: File, surEnvoi: (envoye: Long, total: Long) -> Unit): ResultatShell =
        withContext(Dispatchers.IO) {
            verrou.withLock {
                if (session == null) reprendre()
                val active = session ?: return@withLock ResultatShell.indisponible(motifAucuneSession())
                val total = apk.length()
                val delai = DELAI_INSTALLATION_MS + total / OCTETS_PAR_MO * DELAI_PAR_MO_MS

                sousSurveillance(active, delai) {
                    SourceComptee(apk.source(), total, surEnvoi).use { source ->
                        active.install(source, total, *OPTIONS_INSTALLATION)
                    }
                }.fold(
                    onSuccess = { issue ->
                        when (issue) {
                            is InstallResult.Success -> ResultatShell(code = 0, sortie = "Success")
                            is InstallResult.Failure -> ResultatShell(code = 1, sortie = issue.reason.trim())
                        }
                    },
                    onFailure = { erreur ->
                        Log.w(TAG, "Installation interrompue" + detail(apk.name), erreur)
                        fermerSession()
                        val rupture = rupture(erreur, delaiDepasse = erreur is DelaiDepasse)
                        signalerRupture(rupture)
                        ResultatShell.indisponible(rupture.motif)
                    },
                )
            }
        }

    /**
     * Écrit un fichier sur le téléviseur, comme `adb push`. Hors du chemin d'[executer], comme l'installation :
     * un envoi ne se rejoue pas. Le délai ne porte pas sur la durée — un film prend son temps — mais sur le
     * silence : rien de parti pendant [DELAI_SILENCE_MS], et la session est fermée.
     *
     * Annuler n'interrompt que ce fichier : dadb ferme son flux, la session reste ouverte pour la suite.
     */
    override suspend fun envoyer(
        source: InputStream,
        taille: Long,
        chemin: String,
        date: Long,
        annule: () -> Boolean,
        surEnvoi: (envoye: Long) -> Unit,
    ): ResultatShell = withContext(Dispatchers.IO) {
        source.use { flux ->
            verrou.withLock {
                if (session == null) reprendre()
                val active = session ?: return@withLock ResultatShell.indisponible(motifAucuneSession())
                val activite = AtomicLong(System.currentTimeMillis())

                sousVeille(active, activite, DELAI_SILENCE_MS) {
                    SourceEnvoi(flux.source(), taille, annule, activite, surEnvoi).use { lue ->
                        active.push(lue, chemin, MODE_FICHIER, date.takeIf { it > 0 } ?: System.currentTimeMillis())
                    }
                }.fold(
                    onSuccess = { reponse ->
                        when (reponse) {
                            is SyncResult.Success -> ResultatShell(code = 0, sortie = "")
                            is SyncResult.Failure -> ResultatShell(code = 1, sortie = reponse.reason.trim())
                        }
                    },
                    onFailure = { erreur ->
                        if (erreur is EnvoiAnnule) {
                            ResultatShell.indisponible(contexte.getString(R.string.adb_send_cancelled))
                        } else {
                            Log.w(TAG, "Envoi interrompu" + detail(chemin), erreur)
                            fermerSession()
                            val rupture = rupture(erreur, delaiDepasse = erreur is SilenceProlonge)
                            signalerRupture(rupture)
                            ResultatShell.indisponible(rupture.motif)
                        }
                    },
                )
            }
        }
    }

    /**
     * Exécute une commande tapée à la main, **une seule fois** : contrairement à [executer], rien ne la
     * rejoue après une rupture, puisque rien ne dit qu'elle le supporte. La sortie est lue au fil de l'eau,
     * pour rendre ce qui est déjà sorti quand le délai coupe une commande qui ne finit pas seule —
     * `logcat` sans `-d`, `top`.
     *
     * Le délai ferme la session, seul moyen d'interrompre la lecture ; la suivante se rouvre d'elle-même,
     * sans rien signaler, puisque le téléviseur n'y est pour rien.
     */
    override suspend fun executerUneFois(commande: String): ReponseDirecte = withContext(Dispatchers.IO) {
        verrou.withLock {
            if (session == null) reprendre()
            val active = session
                ?: return@withLock ReponseDirecte(null, "", Interruption.CONNEXION, motifAucuneSession())
            val recue = ByteArrayOutputStream()
            var code: Int? = null

            sousSurveillance(active, ConsoleAdb.DELAI_MAX_S * 1_000L) {
                active.openShell(commande).use { flux ->
                    while (code == null) {
                        when (val paquet = flux.read()) {
                            is AdbShellPacket.Exit -> code = paquet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                            else -> recue.write(paquet.payload)
                        }
                    }
                }
            }.fold(
                onSuccess = { ReponseDirecte(code = code, sortie = texteDe(recue)) },
                onFailure = { erreur ->
                    Log.w(TAG, "Commande libre interrompue" + detail(commande), erreur)
                    fermerSession()
                    if (erreur is DelaiDepasse) {
                        ReponseDirecte(null, texteDe(recue), Interruption.DELAI, motifDelai())
                    } else {
                        val rupture = rupture(erreur, delaiDepasse = false)
                        signalerRupture(rupture)
                        ReponseDirecte(null, texteDe(recue), Interruption.CONNEXION, rupture.motif)
                    }
                },
            )
        }
    }

    /**
     * Lit une commande à sortie binaire — `screencap -p` —, sortie standard et sortie d'erreur à part : le
     * protocole shell v2 de dadb les sépare, sans terminal pour traduire les fins de ligne. Rien n'est rejoué :
     * une capture manquée se redemande d'un appui.
     */
    override suspend fun lireBinaire(commande: String): SortieBinaire = withContext(Dispatchers.IO) {
        verrou.withLock {
            if (session == null) reprendre()
            val active = session ?: return@withLock SortieBinaire(null, ByteArray(0), motif = motifAucuneSession())
            val sortie = ByteArrayOutputStream()
            val erreurs = ByteArrayOutputStream()
            var code: Int? = null

            sousSurveillance(active, DELAI_COMMANDE_MS) {
                active.openShell(commande).use { flux ->
                    while (code == null) {
                        when (val paquet = flux.read()) {
                            is AdbShellPacket.Exit -> code = paquet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                            is AdbShellPacket.StdError -> erreurs.write(paquet.payload)
                            else -> sortie.write(paquet.payload)
                        }
                    }
                }
            }.fold(
                onSuccess = { SortieBinaire(code, sortie.toByteArray(), texteDe(erreurs)) },
                onFailure = { erreur ->
                    Log.w(TAG, "Lecture binaire interrompue" + detail(commande), erreur)
                    fermerSession()
                    if (erreur is DelaiDepasse) {
                        SortieBinaire(null, ByteArray(0), motif = motifDelai())
                    } else {
                        val rupture = rupture(erreur, delaiDepasse = false)
                        signalerRupture(rupture)
                        SortieBinaire(null, ByteArray(0), motif = rupture.motif)
                    }
                },
            )
        }
    }

    private fun texteDe(octets: ByteArrayOutputStream): String = String(octets.toByteArray(), Charsets.UTF_8).trimEnd()

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
        val active = session ?: return Issue.Rompue(motifAucuneSession(), ProblemeConnexion.AUTRE)

        // Sans délai maximal réel, un téléviseur qui se fige ou s'endort en pleine commande
        // bloquerait l'application pour toujours — et le verrou avec elle.
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
                Log.w(TAG, (if (delaiDepasse) "Délai dépassé" else "Commande interrompue") + detail(commande), erreur)
                fermerSession()
                rupture(erreur, delaiDepasse)
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
        if (!repriseAutorisee) return false
        val (hote, port) = cible ?: return false
        val maintenant = System.currentTimeMillis()
        if (maintenant - dernierEchecReprise < REPOS_APRES_ECHEC_MS) return false

        Log.i(TAG, "Session rompue, reprise" + detail("$hote:$port"))
        _connexion.value = _connexion.value.copy(etat = EtatConnexion.CONNEXION)
        return when (val issue = ouvrir(hote, port, DELAI_REPRISE_MS)) {
            is Ouverture.Reussie -> {
                session = issue.session
                dernierEchecReprise = 0L
                _connexion.value = ConnexionUi(EtatConnexion.CONNECTE, hote, port)
                true
            }

            is Ouverture.Echouee -> {
                Log.w(TAG, "Reprise impossible" + detail("$hote:$port"), issue.erreur)
                dernierEchecReprise = maintenant
                false
            }
        }
    }

    /**
     * Exécute un appel bloquant de dadb avec un **vrai** délai maximal.
     *
     * `withTimeout` n'y suffit pas : il annule la coroutine, pas la lecture de socket en cours,
     * qui continuerait d'attendre — verrou tenu, application figée. Fermer la session depuis une
     * autre coroutine, si : la lecture lève aussitôt une exception, rendue ici en [DelaiDepasse].
     */
    private suspend fun <T> sousSurveillance(active: Dadb, delaiMs: Long, appel: () -> T): Result<T> = coroutineScope {
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

    /** Une session perdue : le motif, dans la langue de la personne, et sa cause pour l'écran de connexion. */
    private fun rupture(erreur: Throwable, delaiDepasse: Boolean): Issue.Rompue =
        if (delaiDepasse) {
            Issue.Rompue(motifDelai(), ProblemeConnexion.DELAI)
        } else {
            Issue.Rompue(
                erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName,
                diagnostic(erreur),
            )
        }

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

    // Ces motifs remontent dans les résultats du moteur, à côté des réponses du téléviseur.
    private fun motifAucuneSession(): String = contexte.getString(R.string.adb_no_session)

    private fun motifDelai(): String = contexte.getString(R.string.adb_timeout)

    private companion object {
        const val TAG = "TVSlim/Adb"

        /** Ouverture TCP : un téléviseur allumé sur le réseau local répond en quelques millisecondes. */
        const val DELAI_TCP_MS = 5_000

        /** Large : la connexion attend que quelqu'un accepte la demande sur le téléviseur. */
        const val DELAI_CONNEXION_MS = 45_000L

        /**
         * Une commande de gestion de paquets répond en quelques dizaines de millisecondes ;
         * `dumpsys meminfo` peut demander plusieurs secondes sur un petit boîtier. Comme Windows.
         */
        const val DELAI_COMMANDE_MS = 30_000L

        /** Court : une reprise ne demande aucune validation, elle aboutit ou l'appareil dort. */
        const val DELAI_REPRISE_MS = 12_000L

        /** Après un échec de reprise, on laisse le téléviseur tranquille un moment. */
        const val REPOS_APRES_ECHEC_MS = 20_000L

        /** Une lecture répond en quelques centaines de millisecondes ; au-delà de deux secondes, on le note. */
        const val SEUIL_LENTEUR_MS = 2_000L

        /** La part fixe du délai d'une installation : Android vérifie l'application avant de répondre. */
        const val DELAI_INSTALLATION_MS = 120_000L

        /** Plus le temps d'envoi : deux secondes par mégaoctet, un Wi-Fi médiocre compris. */
        const val DELAI_PAR_MO_MS = 2_000L
        const val OCTETS_PAR_MO = 1_000_000L

        /** Une minute sans qu'un octet parte : la liaison ne fait plus rien passer. */
        const val DELAI_SILENCE_MS = 60_000L

        /** `rw-r--r--` : ce que pose `adb push` ; le stockage partagé n'en tient de toute façon pas compte. */
        const val MODE_FICHIER = 0b110_100_100

        /** `-r` remplace une version en place en gardant ses données ; `-t` admet une build de test. */
        val OPTIONS_INSTALLATION = arrayOf("-r", "-t")
    }
}

/**
 * Compte les octets qui partent : dadb ne dit rien pendant un envoi. Un signe par centième, et non à
 * chaque bloc de huit kilo-octets — l'écran n'a que faire de dix mille mises à jour.
 */
private class SourceComptee(
    source: Source,
    private val total: Long,
    private val surEnvoi: (envoye: Long, total: Long) -> Unit,
) : ForwardingSource(source) {

    private var envoye = 0L
    private var signale = 0L
    private val pas = maxOf(total / 100, 64L * 1024)

    override fun read(sink: Buffer, byteCount: Long): Long {
        val lus = super.read(sink, byteCount)
        if (lus > 0) envoye += lus
        val fin = lus < 0 || envoye >= total
        if (envoye > signale && (fin || envoye - signale >= pas)) {
            signale = envoye
            surEnvoi(envoye, total)
        }
        return lus
    }
}
