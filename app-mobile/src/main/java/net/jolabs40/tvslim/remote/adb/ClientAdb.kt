package net.jolabs40.tvslim.remote.adb

import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.io.File
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
 * La clé privée ne quitte jamais le stockage interne de l'application.
 */
@Singleton
class ClientAdb @Inject constructor(
    @ApplicationContext private val contexte: Context,
) : ExecuteurCommande {

    private val _connexion = MutableStateFlow(ConnexionUi())
    val connexion: StateFlow<ConnexionUi> = _connexion.asStateFlow()

    private val verrou = Mutex()
    private var session: Dadb? = null

    private val dossierCles: File by lazy {
        File(contexte.filesDir, "adb").apply { mkdirs() }
    }

    /** Paire de clés propre à cette installation, créée une fois pour toutes. */
    private fun cles(): AdbKeyPair {
        val privee = File(dossierCles, "adbkey")
        val publique = File(dossierCles, "adbkey.pub")
        if (!privee.exists() || !publique.exists()) {
            AdbKeyPair.generate(privee, publique)
        }
        return AdbKeyPair.read(privee, publique)
    }

    suspend fun connecter(hote: String, port: Int = PORT_ADB_PAR_DEFAUT): Boolean =
        withContext(Dispatchers.IO) {
            verrou.withLock {
                fermerSession()
                _connexion.value = ConnexionUi(EtatConnexion.CONNEXION, hote, port)
                try {
                    val ouverte = withTimeoutOrNull(DELAI_CONNEXION_MS) {
                        Dadb.create(hote, port, cles())
                    } ?: throw java.net.SocketTimeoutException(MESSAGE_ATTENTE_AUTORISATION)
                    session = ouverte
                    _connexion.value = ConnexionUi(EtatConnexion.CONNECTE, hote, port)
                    true
                } catch (erreur: Throwable) {
                    Log.w(TAG, "Connexion à $hote:$port impossible", erreur)
                    _connexion.value = ConnexionUi(
                        etat = EtatConnexion.ERREUR,
                        hote = hote,
                        port = port,
                        message = diagnostic(erreur),
                    )
                    false
                }
            }
        }

    fun deconnecter() {
        fermerSession()
        _connexion.value = ConnexionUi()
    }

    override suspend fun executer(commande: String): ResultatShell = withContext(Dispatchers.IO) {
        verrou.withLock {
            val active = session
                ?: return@withLock ResultatShell.indisponible("Aucun téléviseur connecté.")

            // Sans délai maximal, un téléviseur qui se fige ou s'endort en pleine commande
            // bloquerait l'application pour toujours — et le verrou avec elle. Fermer la
            // session est ce qui débloque réellement la lecture en cours.
            val reponse = withTimeoutOrNull(DELAI_COMMANDE_MS) {
                runCatching { active.shell(commande) }
            }

            when {
                reponse == null -> {
                    Log.w(TAG, "Délai dépassé : $commande")
                    fermerSession()
                    signalerRupture(MESSAGE_DELAI)
                    ResultatShell.indisponible(MESSAGE_DELAI)
                }

                reponse.isFailure -> {
                    val erreur = reponse.exceptionOrNull() ?: IllegalStateException()
                    Log.w(TAG, "Commande refusée : $commande", erreur)
                    fermerSession()
                    signalerRupture(diagnostic(erreur))
                    ResultatShell.indisponible(diagnostic(erreur))
                }

                else -> reponse.getOrThrow().let { sortie ->
                    ResultatShell(
                        code = sortie.exitCode,
                        sortie = listOf(sortie.output, sortie.errorOutput)
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                            .trim(),
                    )
                }
            }
        }
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

        const val MESSAGE_DELAI =
            "Le téléviseur n'a pas répondu à temps. Vérifiez qu'il est allumé et réessayez."

        const val MESSAGE_ATTENTE_AUTORISATION =
            "timed out: la demande d'autorisation attend peut-être sur le téléviseur."
    }
}
