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
import javax.inject.Inject
import javax.inject.Singleton

/** Un téléviseur qui s'annonce sur le réseau local comme joignable en ADB. */
data class AppareilDecouvert(
    val nom: String,
    val hote: String,
    val port: Int,
)

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
        val aResoudre = ArrayDeque<NsdServiceInfo>()
        var resolutionEnCours = false

        fun publier() = trySend(trouves.values.sortedBy { it.hote })

        // Une résolution à la fois : NsdManager refuse les demandes simultanées.
        fun resoudreSuivant() {
            if (resolutionEnCours) return
            val service = synchronized(aResoudre) { aResoudre.removeFirstOrNull() } ?: return
            resolutionEnCours = true
            gestionnaire.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, code: Int) {
                        Log.w(TAG, "Résolution impossible pour ${info?.serviceName} ($code)")
                        resolutionEnCours = false
                        resoudreSuivant()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val adresse = info.host?.hostAddress
                        if (adresse != null) {
                            trouves[info.serviceName] = AppareilDecouvert(
                                nom = info.serviceName,
                                hote = adresse,
                                port = info.port,
                            )
                            publier()
                        }
                        resolutionEnCours = false
                        resoudreSuivant()
                    }
                },
            )
        }

        // Un écouteur par type : NsdManager en refuse un qui serait déjà enregistré ailleurs.
        fun ecouteur() = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, code: Int) {
                Log.w(TAG, "Découverte impossible pour $type ($code)")
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

        val ecoutes = listOf(TYPE_ADB, TYPE_ADB_TLS).mapNotNull { type ->
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
    }
}
