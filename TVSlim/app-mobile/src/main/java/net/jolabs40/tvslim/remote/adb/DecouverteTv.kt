package net.jolabs40.tvslim.remote.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** A TV announcing itself on the local network as reachable over ADB. */
data class AppareilDecouvert(
    val nom: String,
    val hote: String,
    val port: Int,
    /** User-given device name, when one can be found. */
    val nomConvivial: String? = null,
) {
    val libelle: String get() = nomConvivial ?: hote
}

/**
 * Finds TVs over mDNS.
 *
 * A device with network debugging enabled publishes `_adb._tcp`, which is how `adb mdns services` lists them.
 * This spares the user a QR code on the TV, or an IP address that changes with the DHCP lease.
 *
 * Service resolution is serialized: `NsdManager` rejects concurrent requests with `FAILURE_ALREADY_ACTIVE`,
 * and a home network often announces several services at once.
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

        // The ADB service only carries a serial number. The built-in Chromecast publishes `_googlecast._tcp`
        // with the user-given name ("Living room"); the two are matched by IP address.
        val nomsParHote = ConcurrentHashMap<String, String>()

        val aResoudre = ArrayDeque<NsdServiceInfo>()

        // Atomic: binder threads read and write it, and two services found at once could both see `false`,
        // causing FAILURE_ALREADY_ACTIVE. Claim the slot before dequeuing, release it if the queue was empty.
        val resolutionEnCours = AtomicBoolean(false)

        fun publier() = trySend(
            trouves.values
                .map { it.copy(nomConvivial = nomsParHote[it.hote]) }
                .sortedBy { it.libelle },
        )

        // One resolution at a time: NsdManager rejects concurrent requests.
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
                        if (adresse != null && adresse.substringBefore('%') in adressesDuTelephone()) {
                            // The phone's own wireless debugging is announced too. It is not a TV, and its
                            // encrypted connection used to hang the app.
                            Log.d(TAG, "Le téléphone lui-même, écarté" + detail("$adresse:${info.port}"))
                        } else if (adresse != null) {
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

        // One listener per type: NsdManager rejects a listener that is already registered.
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

        // Not `_adb-tls-connect._tcp`: Android 11+ wireless debugging uses an encrypted connection dadb cannot
        // open. Such devices (the phone itself, typically) were listed but unreachable, and connecting hung the app.
        val ecoutes = listOf(TYPE_ADB, TYPE_CAST).mapNotNull { type ->
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

        /** Classic network debugging (TVs on port 5555), the only kind dadb can connect to. */
        const val TYPE_ADB = "_adb._tcp"

        /** Built-in Chromecast, which carries the user-given device name. */
        const val TYPE_CAST = "_googlecast._tcp"

        /**
         * All of the phone's addresses, re-read on each resolution since the DHCP lease may change. IPv6 zone
         * suffixes (`%wlan0`) are stripped.
         */
        fun adressesDuTelephone(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { carte -> carte.inetAddresses.asSequence() }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .toSet()
        }.getOrDefault(emptySet())

        /** `fn` (friendly name) from the cast service attributes, falling back to `md` (model). */
        fun nomConvivial(info: NsdServiceInfo): String? {
            val attributs = info.attributes ?: return null
            return listOf("fn", "md")
                .firstNotNullOfOrNull { cle -> attributs[cle]?.toString(Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() }
        }
    }
}
