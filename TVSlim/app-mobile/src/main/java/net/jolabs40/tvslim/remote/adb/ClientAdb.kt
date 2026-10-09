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

/** Why a connection failed; the UI turns it into a localized explanation. */
enum class ProblemeConnexion { REFUSEE, DELAI, NON_AUTORISEE, INJOIGNABLE, AUTRE }

data class ConnexionUi(
    val etat: EtatConnexion = EtatConnexion.DECONNECTE,
    val hote: String = "",
    val port: Int = PORT_ADB_PAR_DEFAUT,
    val probleme: ProblemeConnexion? = null,
    /** Raw technical message, shown in small print under the explanation. */
    val detail: String = "",
)

const val PORT_ADB_PAR_DEFAUT = 5555

/**
 * ADB connection from the phone to a TV in pure Kotlin (dadb): no `adb` binary, no ADB server, no computer.
 *
 * The first connection shows the "Allow debugging?" prompt on the TV. Once accepted, the public key is stored
 * in the TV's `/data/misc/adb/adb_keys`, so the authorization survives reboots (a local privileged service
 * would die at every shutdown). The private key never leaves the phone and is stored encrypted ([DepotCles]).
 *
 * Two dadb pitfalls:
 *  - dadb only opens the socket on the first command. [connecter] forces it, so "connected" is not shown
 *    before the TV has accepted anything.
 *  - `withTimeout` does not interrupt a blocked socket read. Timeouts are enforced by [sousSurveillance],
 *    which closes the session from another coroutine.
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

    /** Last TV the user connected to; reconnects target it. */
    @Volatile
    private var cible: Pair<String, Int>? = null

    /** Time of the last failed reconnect, so it is not retried on every command. */
    private var dernierEchecReprise = 0L

    /** False for a secondary session ([ouvrirSeconde]): once broken, it is not reopened. */
    private var repriseAutorisee = true

    /**
     * Opens the connection and waits for the TV to accept it. [discret] is for attempts the user did not
     * ask for (returning to the app): failure is common there (TV off) and shows no error.
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
     * Opens a second session to the same TV for the long reads prefetched on connect (apps, `dumpsys meminfo`,
     * storage), leaving the main session free for user actions. The key is already authorized, so the TV shows
     * no prompt.
     *
     * It never reconnects by itself; a failed prefetch is redone on demand through the main session. The caller
     * closes it ([deconnecter]), which also interrupts a read in progress. Returns null if no TV is connected or
     * it does not answer.
     */
    suspend fun ouvrirSeconde(): ClientAdb? {
        val (hote, port) = cible ?: return null
        val seconde = ClientAdb(contexte, depotCles).apply { repriseAutorisee = false }
        var ouverte = false
        try {
            ouverte = seconde.connecter(hote, port, discret = true)
        } finally {
            // If cancelled midway, the connection may still have succeeded: do not leave it open.
            if (!ouverte) seconde.deconnecter()
        }
        return seconde.takeIf { ouverte }
    }

    fun deconnecter() {
        // Deliberately outside the lock: closing the socket is what unblocks a pending command,
        // which holds the lock.
        fermerSession()
        // Disconnecting is explicit: nothing may reopen the session behind the user's back.
        cible = null
        _connexion.value = ConnexionUi()
    }

    /**
     * Runs a command, reconnecting once and replaying it if the session dropped (a sleeping TV closes it
     * silently, which only shows on the next command).
     *
     * Replay is safe because every command sent here is idempotent: reads, `pm disable-user`, `pm enable`,
     * `am force-stop`, `settings put`, opening a store page. Non-idempotent commands must not use this path.
     */
    override suspend fun executer(commande: String): ResultatShell = withContext(Dispatchers.IO) {
        val demande = System.currentTimeMillis()
        verrou.withLock {
            val obtenu = System.currentTimeMillis()
            val resultat = executerSousVerrou(commande)
            // A folder read once took over ten seconds and could not be reproduced. Log slow commands to tell
            // whether they wait on the lock, the TV or a reconnect.
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

    /** One attempt; if the session dropped, one reconnect and one replay. */
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
     * Streams an APK to `cmd package install` without copying it to the TV first.
     *
     * Not routed through [executer]: a large upload is never replayed silently. A session that dropped before
     * the upload is reopened; a break during it is reported. The timeout grows with the file size and leaves
     * Android time to verify the package.
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
     * Writes a file to the TV, like `adb push`. Not replayed, like [installer]. The timeout applies to
     * inactivity rather than total duration: nothing sent for [DELAI_SILENCE_MS] closes the session.
     *
     * Cancelling stops only this file: dadb closes its stream and the session stays open.
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
     * Runs a user-typed command exactly once: unlike [executer], it is never replayed since it may not be
     * idempotent. Output is streamed so that commands that never end (`logcat` without `-d`, `top`) still
     * return what they printed when the timeout cuts them.
     *
     * The timeout closes the session (the only way to stop the read); the next command reopens it silently.
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
     * Runs a command with binary output (`screencap -p`). dadb's shell v2 protocol keeps stdout and stderr apart
     * and uses no terminal, so line endings are not translated. Not replayed.
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

    /** A command's outcome: an answer, or a session to reopen. */
    private sealed interface Issue {
        data class Repondu(val resultat: ResultatShell) : Issue
        data class Rompue(val motif: String, val probleme: ProblemeConnexion) : Issue
    }

    private suspend fun ouvrir(hote: String, port: Int, delaiMs: Long): Ouverture {
        val ouverte = try {
            // The socket read timeout is DELAI_CONNEXION_MS, time for the user to accept the prompt on the TV.
            // The shorter reconnect timeout is enforced by the watchdog.
            Dadb.create(hote, port, depotCles.paire(), DELAI_TCP_MS, DELAI_CONNEXION_MS.toInt())
        } catch (erreur: Exception) {
            return Ouverture.Echouee(erreur, diagnostic(erreur))
        }
        // A harmless round trip forces the handshake, and thus the authorization prompt, now.
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

        // Without a real timeout, a TV that freezes or sleeps mid-command would hold the lock forever.
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
     * Reopens the session to the same TV; the key is already authorized, so no prompt appears.
     *
     * After a failure, reconnects pause for [REPOS_APRES_ECHEC_MS]. Otherwise disabling eighty packages on a TV
     * that was just turned off would attempt eighty reconnects.
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
     * Runs a blocking dadb call with a real timeout.
     *
     * `withTimeout` cancels the coroutine but not the blocked socket read, which would keep the lock. Closing
     * the session from another coroutine makes the read throw at once; that is reported as [DelaiDepasse].
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

    /** A lost session: a localized reason, plus its cause for the connection screen. */
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

    // These reasons end up in the engine's results, next to the TV's own output.
    private fun motifAucuneSession(): String = contexte.getString(R.string.adb_no_session)

    private fun motifDelai(): String = contexte.getString(R.string.adb_timeout)

    private companion object {
        const val TAG = "TVSlim/Adb"

        /** TCP connect: a TV on the local network answers within milliseconds. */
        const val DELAI_TCP_MS = 5_000

        /** Long: the connection waits for someone to accept the prompt on the TV. */
        const val DELAI_CONNEXION_MS = 45_000L

        /** Package commands take tens of ms; `dumpsys meminfo` can take seconds on a small box. Same as Windows. */
        const val DELAI_COMMANDE_MS = 30_000L

        /** Short: a reconnect needs no prompt, it either succeeds or the device is asleep. */
        const val DELAI_REPRISE_MS = 12_000L

        const val REPOS_APRES_ECHEC_MS = 20_000L

        /** Reads normally take a few hundred ms; anything slower is logged. */
        const val SEUIL_LENTEUR_MS = 2_000L

        /** Fixed part of the install timeout: Android verifies the package before answering. */
        const val DELAI_INSTALLATION_MS = 120_000L

        /** Plus upload time: two seconds per megabyte, enough for poor Wi-Fi. */
        const val DELAI_PAR_MO_MS = 2_000L
        const val OCTETS_PAR_MO = 1_000_000L

        /** A minute without a byte sent means the link is dead. */
        const val DELAI_SILENCE_MS = 60_000L

        /** `rw-r--r--`, as `adb push` sets; shared storage ignores it anyway. */
        const val MODE_FICHIER = 0b110_100_100

        /** `-r` replaces an installed version and keeps its data; `-t` allows test builds. */
        val OPTIONS_INSTALLATION = arrayOf("-r", "-t")
    }
}

/**
 * Counts bytes sent, since dadb reports no progress. Reports every 1% (at least 64 KiB) rather than on every
 * 8 KiB block.
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
