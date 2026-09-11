package net.jolabs40.tvslim.remote.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Un téléviseur qui s'annonce sur le réseau local comme joignable en ADB. */
data class AppareilDecouvert(
    val nom: String,
    val hote: String,
    val port: Int,
    /** Le nom que la personne a donné à l'appareil, s'il se laisse trouver. */
    val nomConvivial: String? = null,
) {
    /** Ce qu'on affiche : le nom donné par la personne, sinon l'adresse. */
    val libelle: String get() = nomConvivial ?: hote
}

/**
 * Découverte des téléviseurs par mDNS.
 *
 * Un appareil dont le débogage réseau est actif publie un service `_adb._tcp` : c'est ainsi que
 * `adb mdns services` les liste, et il n'y a aucune raison que le compagnon s'en prive. Trouver
 * la machine tout seul évite d'aller allumer le téléviseur pour lire un QR code, ou de retenir
 * une adresse IP qui change au gré du bail DHCP.
 *
 * La résolution des services est **sérialisée** : `NsdManager` refuse les demandes concurrentes
 * avec `FAILURE_ALREADY_ACTIVE`, et un réseau domestique en annonce facilement plusieurs d'un
 * coup.
 */
@Singleton
class DecouverteTv @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    fun flux(): Flow<List<AppareilDecouvert>> = callbackFlow {
        val gestionnaire = contexte.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (gestionnaire == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val trouves = ConcurrentHashMap<String, AppareilDecouvert>()

        /**
         * Le service ADB ne porte qu'un numéro de série. Le Chromecast intégré, lui, publie
         * `_googlecast._tcp` avec le nom que la personne a donné à l'appareil — « Salon »,
         * « TV du bas ». On les rapproche par adresse IP.
         */
        val nomsParHote = ConcurrentHashMap<String, String>()

        val aResoudre = ArrayDeque<NsdServiceInfo>()

        // Un drapeau nu ne suffit pas : il est lu et écrit depuis les fils du binder, et deux
        // services trouvés en même temps pouvaient tous deux le voir à `false` — c'est
        // exactement le FAILURE_ALREADY_ACTIVE qu'on cherche à éviter. On réserve la place
        // avant de piocher, et on la rend si la file était vide.
        val resolutionEnCours = AtomicBoolean(false)

        fun publier() = trySend(
            trouves.values
                .map { it.copy(nomConvivial = nomsParHote[it.hote]) }
                .sortedBy { it.libelle },
        )

        // Une résolution à la fois : NsdManager refuse les demandes simultanées.
        fun resoudreSuivant() {
            if (!resolutionEnCours.compareAndSet(false, true)) return
            val service = synchronized(aResoudre) { aResoudre.removeFirstOrNull() }
            if (service == null) {
                resolutionEnCours.set(false)
                return
            }
            gestionnaire.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, code: Int) {
                        Log.w(TAG, "Résolution impossible ($code)" + detail(info?.serviceName.orEmpty()))
                        resolutionEnCours.set(false)
                        resoudreSuivant()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val adresse = info.host?.hostAddress
                        if (adresse != null) {
                            if (info.serviceType.contains(TYPE_CAST.trim('.'))) {
                                nomConvivial(info)?.let { nomsParHote[adresse] = it }
                            } else {
                                trouves[info.serviceName] = AppareilDecouvert(
                                    nom = info.serviceName,
                                    hote = adresse,
                                    port = info.port,
                                )
                            }
                            publier()
                        }
                        resolutionEnCours.set(false)
                        resoudreSuivant()
                    }
                },
            )
        }

        // Un écouteur par type : NsdManager en refuse un qui serait déjà enregistré ailleurs.
        fun ecouteur() = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, code: Int) {
                Log.w(TAG, "Découverte impossible ($code)" + detail(type.orEmpty()))
            }

            override fun onStopDiscoveryFailed(type: String?, code: Int) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(aResoudre) { aResoudre.addLast(service) }
                resoudreSuivant()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                trouves.remove(service.serviceName)
                publier()
            }
        }

        val ecoutes = listOf(TYPE_ADB, TYPE_ADB_TLS, TYPE_CAST).mapNotNull { type ->
            val ecoute = ecouteur()
            runCatching {
                gestionnaire.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, ecoute)
                ecoute
            }.getOrNull()
        }

        awaitClose {
            ecoutes.forEach { runCatching { gestionnaire.stopServiceDiscovery(it) } }
        }
    }

    private companion object {
        const val TAG = "TVSlim/Decouverte"

        /** Débogage réseau classique, celui des téléviseurs sur le port 5555. */
        const val TYPE_ADB = "_adb._tcp"

        /** Débogage sans fil d'Android 11+, une fois l'appareil appairé. */
        const val TYPE_ADB_TLS = "_adb-tls-connect._tcp"

        /** Chromecast intégré : c'est lui qui porte le nom donné à l'appareil. */
        const val TYPE_CAST = "_googlecast._tcp"

        /** `fn` (friendly name) dans les attributs du service cast, `md` à défaut (modèle). */
        fun nomConvivial(info: NsdServiceInfo): String? {
            val attributs = info.attributes ?: return null
            return listOf("fn", "md")
                .firstNotNullOfOrNull { cle -> attributs[cle]?.toString(Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() }
        }
    }
}
