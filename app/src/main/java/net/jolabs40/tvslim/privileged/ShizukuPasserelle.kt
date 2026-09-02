package net.jolabs40.tvslim.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passerelle vers Shizuku : c'est la seule classe de l'application qui connaît son API.
 *
 * Shizuku expose un process en UID shell (2000). Les commandes qui y sont exécutées sont
 * strictement celles qu'une session ADB pourrait lancer — ni plus, ni moins : aucun root,
 * aucune désinstallation, uniquement des opérations réversibles.
 */
@Singleton
class ShizukuPasserelle @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    private val _etat = MutableStateFlow(EtatPrivilege.INCONNU)
    val etat: StateFlow<EtatPrivilege> = _etat.asStateFlow()

    private var service: IShellService? = null
    private val verrou = Mutex()

    private val connexion = object : ServiceConnection {
        override fun onServiceConnected(nom: ComponentName?, binder: IBinder?) {
            service = binder?.let { IShellService.Stub.asInterface(it) }
            attente?.complete(service != null)
        }

        override fun onServiceDisconnected(nom: ComponentName?) {
            service = null
        }
    }

    private var attente: CompletableDeferred<Boolean>? = null

    private val arguments: Shizuku.UserServiceArgs
        get() = Shizuku.UserServiceArgs(
            ComponentName(contexte.packageName, ShellService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(VERSION_SERVICE)

    init {
        Shizuku.addBinderReceivedListenerSticky { rafraichirEtat() }
        Shizuku.addBinderDeadListener {
            service = null
            _etat.value = EtatPrivilege.SERVICE_ARRETE
        }
        Shizuku.addRequestPermissionResultListener { _, resultat ->
            _etat.value = if (resultat == PackageManager.PERMISSION_GRANTED) {
                EtatPrivilege.PRET
            } else {
                EtatPrivilege.REFUSE
            }
        }
        rafraichirEtat()
    }

    /** Recalcule l'état des privilèges : installé ? service démarré ? autorisation accordée ? */
    fun rafraichirEtat() {
        _etat.value = try {
            when {
                !estInstalle() -> EtatPrivilege.ABSENT
                !Shizuku.pingBinder() -> EtatPrivilege.SERVICE_ARRETE
                Shizuku.isPreV11() -> EtatPrivilege.SERVICE_ARRETE
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> EtatPrivilege.PRET
                Shizuku.shouldShowRequestPermissionRationale() -> EtatPrivilege.REFUSE
                else -> EtatPrivilege.AUTORISATION_REQUISE
            }
        } catch (erreur: Throwable) {
            Log.w(TAG, "Shizuku injoignable", erreur)
            EtatPrivilege.ABSENT
        }
    }

    /** Ouvre la demande d'autorisation de Shizuku. Sans effet si le service n'est pas démarré. */
    fun demanderAutorisation() {
        runCatching {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(CODE_DEMANDE)
            }
        }
    }

    /**
     * Exécute une commande dans le process privilégié.
     * Renvoie un résultat en erreur — jamais une exception — si les privilèges manquent.
     */
    suspend fun executer(commande: String): ResultatShell = withContext(Dispatchers.IO) {
        val instance = assurerService()
            ?: return@withContext ResultatShell.indisponible(
                "Privilèges indisponibles : Shizuku n'est pas prêt.",
            )
        try {
            decouper(instance.executer(commande))
        } catch (erreur: Exception) {
            service = null
            ResultatShell.indisponible(erreur.message ?: erreur.javaClass.simpleName)
        }
    }

    private suspend fun assurerService(): IShellService? = verrou.withLock {
        service?.let { return@withLock it }
        rafraichirEtat()
        if (_etat.value != EtatPrivilege.PRET) return@withLock null

        val promesse = CompletableDeferred<Boolean>()
        attente = promesse
        val demarre = runCatching { Shizuku.bindUserService(arguments, connexion) }.isSuccess
        if (!demarre) return@withLock null
        withTimeoutOrNull(DELAI_LIAISON_MS) { promesse.await() }
        service
    }

    private fun estInstalle(): Boolean = runCatching {
        contexte.packageManager.getPackageInfo(PAQUET_SHIZUKU, 0)
        true
    }.getOrDefault(false)

    private fun decouper(brut: String): ResultatShell {
        val separateur = brut.indexOf('\n')
        if (separateur < 0) return ResultatShell(code = -1, sortie = brut)
        val code = brut.substring(0, separateur).trim().toIntOrNull() ?: -1
        return ResultatShell(code = code, sortie = brut.substring(separateur + 1).trim())
    }

    companion object {
        const val PAQUET_SHIZUKU = "moe.shizuku.privileged.api"
        private const val TAG = "TVSlim/Shizuku"
        private const val CODE_DEMANDE = 4242
        private const val VERSION_SERVICE = 1
        private const val DELAI_LIAISON_MS = 10_000L
    }
}
