package net.jolabs40.tvslim.windows.adb

import dadb.AdbShellPacket
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
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
import net.jolabs40.tvslim.shell.EnvoyeurFichiers
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ExecuteurDirect
import net.jolabs40.tvslim.shell.InstallateurApk
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.shell.LecteurBinaire
import net.jolabs40.tvslim.shell.RecepteurFichiers
import net.jolabs40.tvslim.shell.ReponseDirecte
import net.jolabs40.tvslim.shell.ResultatShell
import net.jolabs40.tvslim.shell.SortieBinaire
import net.jolabs40.tvslim.windows.outils.Traces
import net.jolabs40.tvslim.windows.outils.detail
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.sink
import okio.source
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class EtatConnexion { DECONNECTE, CONNEXION, CONNECTE, ERREUR }

/** Why a connection failed, so the UI can explain it in the user's language. */
enum class ProblemeConnexion { REFUSEE, DELAI, NON_AUTORISEE, INJOIGNABLE, AUTRE }

data class ConnexionUi(
    val etat: EtatConnexion = EtatConnexion.DECONNECTE,
    val hote: String = "",
    val port: Int = PORT_ADB_PAR_DEFAUT,
    val probleme: ProblemeConnexion? = null,
    /** Original technical message, shown in small print under the explanation. */
    val detail: String = "",
)

const val PORT_ADB_PAR_DEFAUT = 5555

/**
 * ADB client from the PC to a TV in pure Kotlin (dadb): no `adb.exe`, no ADB server. Same library as the
 * Android companion.
 *
 * The first connection shows "Allow USB debugging?" on the TV. Once accepted, the public key is stored in
 * `/data/misc/adb/adb_keys` and the authorization survives reboots.
 *
 * Two dadb behaviours to work around:
 *  - dadb opens the socket lazily on the first command, so [connecter] sends one right away and only reports
 *    connected once the TV has accepted;
 *  - `withTimeout` does not interrupt a blocked socket read, so timeouts are enforced by [sousSurveillance],
 *    which closes the session from another thread.
 */
class ClientAdb(
    private val depotCles: DepotCles,
) : ExecuteurCommande, InstallateurApk, ExecuteurDirect, EnvoyeurFichiers, RecepteurFichiers, LecteurBinaire {

    private val _connexion = MutableStateFlow(ConnexionUi())
    val connexion: StateFlow<ConnexionUi> = _connexion.asStateFlow()

    private val verrou = Mutex()

    @Volatile
    private var session: Dadb? = null

    /** Last TV the user connected to; every reconnection targets it. */
    @Volatile
    private var cible: Pair<String, Int>? = null

    /** Time of the last failed reconnection, so it is not retried on every command. */
    private var dernierEchecReprise = 0L

    /** False for a secondary session ([ouvrirSeconde]), which never reopens once broken. */
    private var repriseAutorisee = true

    /**
     * Opens the connection and waits for the TV to accept it. [discret] is for attempts the user did not ask for
     * (e.g. when the window regains focus): failure is usual there (TV off) and no error is shown.
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

    /**
     * Opens a second session to the same TV for the long reads prefetched on connection (apps, `dumpsys meminfo`,
     * storage), keeping the main session free. The key is already authorized, so nothing shows on the TV.
     *
     * It never reopens by itself; a failed prefetch is redone on demand by the main session. The caller closes it
     * with [deconnecter], which is also how an ongoing read is interrupted. Returns null if no TV is connected or
     * it does not answer.
     */
    suspend fun ouvrirSeconde(): ClientAdb? {
        val (hote, port) = cible ?: return null
        val seconde = ClientAdb(depotCles).apply { repriseAutorisee = false }
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
        // Deliberately without the lock: closing the socket is what unblocks a pending command, which holds it.
        fermerSession()
        // An explicit disconnect must not be undone by an automatic reconnection.
        cible = null
        _connexion.value = ConnexionUi()
    }

    /**
     * Runs a command, reopening the session once and replaying if it was broken (a TV going to sleep drops the
     * session silently).
     *
     * Replay is safe only because every command sent here is idempotent: reads, `pm disable-user`, `pm enable`,
     * `am force-stop`, `settings put`, `pm grant`, opening a store page. Non-idempotent commands must not use
     * this path.
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

    /**
     * Streams an APK to `cmd package install`, without copying it to the TV first.
     *
     * Not routed through [executer]: a multi-megabyte upload is never replayed silently. A session dropped before
     * the upload is reopened; a break during it is reported. The timeout grows with the file size and leaves time
     * for Android's verification and for a Play Protect prompt.
     */
    override suspend fun installer(apk: File, surEnvoi: (envoye: Long, total: Long) -> Unit): ResultatShell =
        withContext(Dispatchers.IO) {
            verrou.withLock {
                if (session == null) reprendre()
                val active = session ?: return@withLock ResultatShell.indisponible(MOTIF_AUCUNE_SESSION)
                val total = apk.length()
                val delai = DELAI_INSTALLATION_MS + total / OCTETS_PAR_MO * DELAI_PAR_MO_MS

                sousSurveillance(active, delai) {
                    SourceComptee(apk.source(), total, surEnvoi).use { source ->
                        active.install(source, total, *OPTIONS_INSTALLATION)
                    }
                }.fold(
                    onSuccess = { reponse ->
                        when (reponse) {
                            is InstallResult.Success -> ResultatShell(code = 0, sortie = "Success")
                            is InstallResult.Failure -> ResultatShell(code = 1, sortie = reponse.reason.trim())
                        }
                    },
                    onFailure = { erreur ->
                        Traces.avertir(TAG, "Installation interrompue" + detail(apk.name), erreur)
                        fermerSession()
                        val rupture = Issue.Rompue(
                            motif = if (erreur is DelaiDepasse) {
                                MOTIF_DELAI
                            } else {
                                erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName
                            },
                            probleme = diagnostic(erreur),
                        )
                        signalerRupture(rupture)
                        ResultatShell.indisponible(rupture.motif)
                    },
                )
            }
        }

    /**
     * Writes a file to the TV, like `adb push`. Not replayed. The timeout applies to inactivity, not total
     * duration: the session is closed if nothing is sent for [DELAI_SILENCE_MS].
     *
     * Cancelling stops only this file; dadb closes its stream and the session stays open.
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
                val active = session ?: return@withLock ResultatShell.indisponible(MOTIF_AUCUNE_SESSION)
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
                            ResultatShell.indisponible(MOTIF_ANNULE)
                        } else {
                            Traces.avertir(TAG, "Envoi interrompu" + detail(chemin), erreur)
                            fermerSession()
                            val rupture = Issue.Rompue(
                                motif = if (erreur is SilenceProlonge) {
                                    MOTIF_DELAI
                                } else {
                                    erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName
                                },
                                probleme = diagnostic(erreur),
                            )
                            signalerRupture(rupture)
                            ResultatShell.indisponible(rupture.motif)
                        }
                    },
                )
            }
        }
    }

    /**
     * Reads a file from the TV, like `adb pull`. Not replayed; same inactivity timeout, and cancelling stops only
     * this file.
     *
     * A local write failure is not a lost connection: the session stays open and the error is returned with code 1.
     */
    override suspend fun recevoir(
        chemin: String,
        destination: OutputStream,
        taille: Long,
        annule: () -> Boolean,
        surRecu: (recu: Long) -> Unit,
    ): ResultatShell = withContext(Dispatchers.IO) {
        verrou.withLock {
            if (session == null) reprendre()
            val active = session ?: return@withLock ResultatShell.indisponible(MOTIF_AUCUNE_SESSION)
            val activite = AtomicLong(System.currentTimeMillis())

            sousVeille(active, activite, DELAI_SILENCE_MS) {
                // The sink does not close the stream; the local writer closes and commits it.
                val puits = PuitsReception(destination.sink(), taille, annule, activite, surRecu)
                active.pull(puits, chemin).also { puits.flush() }
            }.fold(
                onSuccess = { reponse ->
                    when (reponse) {
                        is SyncResult.Success -> ResultatShell(code = 0, sortie = "")
                        is SyncResult.Failure -> ResultatShell(code = 1, sortie = reponse.reason.trim())
                    }
                },
                onFailure = { erreur ->
                    when (erreur) {
                        is ReceptionAnnulee -> ResultatShell.indisponible(MOTIF_COPIE_ANNULEE)
                        is EcritureLocaleEchouee -> ResultatShell(code = 1, sortie = erreur.message.orEmpty())
                        else -> {
                            Traces.avertir(TAG, "Copie interrompue" + detail(chemin), erreur)
                            fermerSession()
                            val rupture = Issue.Rompue(
                                motif = if (erreur is SilenceProlonge) {
                                    MOTIF_DELAI
                                } else {
                                    erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName
                                },
                                probleme = diagnostic(erreur),
                            )
                            signalerRupture(rupture)
                            ResultatShell.indisponible(rupture.motif)
                        }
                    }
                },
            )
        }
    }

    /**
     * Runs a user-typed command exactly once: unlike [executer], it is never replayed since it may not be
     * idempotent. Output is read as it arrives, so a command that never ends (`logcat` without `-d`, `top`) still
     * returns what it printed when the timeout cuts it.
     *
     * The timeout closes the session (the only way to interrupt the read); the next command reopens it without
     * reporting an error.
     */
    override suspend fun executerUneFois(commande: String): ReponseDirecte = withContext(Dispatchers.IO) {
        verrou.withLock {
            if (session == null) reprendre()
            val active = session
                ?: return@withLock ReponseDirecte(null, "", Interruption.CONNEXION, MOTIF_AUCUNE_SESSION)
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
                    Traces.avertir(TAG, "Commande libre interrompue" + detail(commande), erreur)
                    fermerSession()
                    if (erreur is DelaiDepasse) {
                        ReponseDirecte(null, texteDe(recue), Interruption.DELAI, "délai dépassé")
                    } else {
                        val rupture = Issue.Rompue(
                            motif = erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName,
                            probleme = diagnostic(erreur),
                        )
                        signalerRupture(rupture)
                        ReponseDirecte(null, texteDe(recue), Interruption.CONNEXION, rupture.motif)
                    }
                },
            )
        }
    }

    /**
     * Runs a command with binary output (`screencap -p`). dadb's shell v2 protocol keeps stdout and stderr apart
     * with no terminal translating line endings. Not replayed.
     */
    override suspend fun lireBinaire(commande: String): SortieBinaire = withContext(Dispatchers.IO) {
        verrou.withLock {
            if (session == null) reprendre()
            val active = session ?: return@withLock SortieBinaire(null, ByteArray(0), motif = MOTIF_AUCUNE_SESSION)
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
                    Traces.avertir(TAG, "Lecture binaire interrompue" + detail(commande), erreur)
                    fermerSession()
                    if (erreur is DelaiDepasse) {
                        SortieBinaire(null, ByteArray(0), motif = MOTIF_DELAI)
                    } else {
                        val rupture = Issue.Rompue(
                            motif = erreur.message?.takeIf { it.isNotBlank() } ?: erreur.javaClass.simpleName,
                            probleme = diagnostic(erreur),
                        )
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

    private sealed interface Issue {
        data class Repondu(val resultat: ResultatShell) : Issue
        data class Rompue(val motif: String, val probleme: ProblemeConnexion) : Issue
    }

    private suspend fun ouvrir(hote: String, port: Int, delaiMs: Long): Ouverture {
        val ouverte = try {
            // Socket read timeout is DELAI_CONNEXION_MS, enough to accept the prompt on the TV. The shorter
            // reconnection timeout is enforced by sousSurveillance.
            Dadb.create(hote, port, depotCles.paire(), DELAI_TCP_MS, DELAI_CONNEXION_MS.toInt())
        } catch (erreur: Exception) {
            return Ouverture.Echouee(erreur, diagnostic(erreur))
        }
        // A harmless round trip forces the handshake, and so the authorization prompt, now.
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
     * Reopens the session to the same TV; the key is already authorized, so no prompt appears.
     *
     * After a failure, reconnection pauses for [REPOS_APRES_ECHEC_MS], otherwise disabling 80 packages on a TV
     * that was just switched off would attempt 80 reconnections.
     */
    private suspend fun reprendre(): Boolean {
        if (!repriseAutorisee) return false
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
     * Runs a blocking dadb call with a hard timeout.
     *
     * `withTimeout` cancels the coroutine but not the blocked socket read, which would keep the lock and freeze
     * the app. Closing the session from another thread makes the read throw at once; that is reported as
     * [DelaiDepasse].
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

        /** A TV that is on, on the local network, accepts TCP within milliseconds. */
        const val DELAI_TCP_MS = 5_000

        /** Long because the user must accept the prompt on the TV. */
        const val DELAI_CONNEXION_MS = 45_000L

        /** Package commands answer in tens of ms; `dumpsys meminfo` can take seconds on a small box. */
        const val DELAI_COMMANDE_MS = 30_000L

        /** Short: a reconnection needs no prompt, it either succeeds or the device is asleep. */
        const val DELAI_REPRISE_MS = 12_000L

        const val REPOS_APRES_ECHEC_MS = 20_000L

        /** Fixed part of the install timeout: Android verifies the app before answering. */
        const val DELAI_INSTALLATION_MS = 120_000L

        /** Plus 2 s per MB of upload, enough for poor Wi-Fi. */
        const val DELAI_PAR_MO_MS = 2_000L
        const val OCTETS_PAR_MO = 1_000_000L

        /** One minute without a byte moving means the link is dead. */
        const val DELAI_SILENCE_MS = 60_000L

        /** `rw-r--r--`, as `adb push` sets; shared storage ignores it anyway. */
        const val MODE_FICHIER = 0b110_100_100

        /** `-r` replaces an installed version keeping its data; `-t` allows test builds. */
        val OPTIONS_INSTALLATION = arrayOf("-r", "-t")

        // These reasons end up in engine results and in the journal, like the shared core's, which are French.
        const val MOTIF_AUCUNE_SESSION = "Aucun téléviseur connecté."
        const val MOTIF_ANNULE = "Envoi annulé."
        const val MOTIF_COPIE_ANNULEE = "Copie annulée."
        const val MOTIF_DELAI =
            "Le téléviseur n'a pas répondu à temps. Vérifiez qu'il est allumé et réessayez."
    }
}

/**
 * Counts uploaded bytes, since dadb reports no progress. Reports every 1% (at least 64 KiB), not every 8 KiB
 * block.
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
