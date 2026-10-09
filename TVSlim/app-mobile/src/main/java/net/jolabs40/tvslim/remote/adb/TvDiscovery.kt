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
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** User-given device name, when one can be found. */
    val friendlyName: String? = null,
) {
    val label: String get() = friendlyName ?: host
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
class TvDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun stream(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val found = ConcurrentHashMap<String, DiscoveredDevice>()

        // The ADB service only carries a serial number. The built-in Chromecast publishes `_googlecast._tcp`
        // with the user-given name ("Living room"); the two are matched by IP address.
        val namesByHost = ConcurrentHashMap<String, String>()

        val toResolve = ArrayDeque<NsdServiceInfo>()

        // Atomic: binder threads read and write it, and two services found at once could both see `false`,
        // causing FAILURE_ALREADY_ACTIVE. Claim the slot before dequeuing, release it if the queue was empty.
        val resolutionInProgress = AtomicBoolean(false)

        fun publish() = trySend(
            found.values
                .map { it.copy(friendlyName = namesByHost[it.host]) }
                .sortedBy { it.label },
        )

        // One resolution at a time: NsdManager rejects concurrent requests.
        fun resolveNext() {
            if (!resolutionInProgress.compareAndSet(false, true)) return
            val service = synchronized(toResolve) { toResolve.removeFirstOrNull() }
            if (service == null) {
                resolutionInProgress.set(false)
                return
            }
            manager.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, code: Int) {
                        Log.w(TAG, "Résolution impossible ($code)" + detail(info?.serviceName.orEmpty()))
                        resolutionInProgress.set(false)
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host?.hostAddress
                        if (address != null && address.substringBefore('%') in phoneAddresses()) {
                            // The phone's own wireless debugging is announced too. It is not a TV, and its
                            // encrypted connection used to hang the app.
                            Log.d(TAG, "Le téléphone lui-même, écarté" + detail("$address:${info.port}"))
                        } else if (address != null) {
                            if (info.serviceType.contains(TYPE_CAST.trim('.'))) {
                                friendlyName(info)?.let { namesByHost[address] = it }
                            } else {
                                found[info.serviceName] = DiscoveredDevice(
                                    name = info.serviceName,
                                    host = address,
                                    port = info.port,
                                )
                            }
                            publish()
                        }
                        resolutionInProgress.set(false)
                        resolveNext()
                    }
                },
            )
        }

        // One listener per type: NsdManager rejects a listener that is already registered.
        fun listener() = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String?, code: Int) {
                Log.w(TAG, "Découverte impossible ($code)" + detail(type.orEmpty()))
            }

            override fun onStopDiscoveryFailed(type: String?, code: Int) = Unit
            override fun onDiscoveryStarted(type: String?) = Unit
            override fun onDiscoveryStopped(type: String?) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                synchronized(toResolve) { toResolve.addLast(service) }
                resolveNext()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.remove(service.serviceName)
                publish()
            }
        }

        // Not `_adb-tls-connect._tcp`: Android 11+ wireless debugging uses an encrypted connection dadb cannot
        // open. Such devices (the phone itself, typically) were listed but unreachable, and connecting hung the app.
        val listeners = listOf(TYPE_ADB, TYPE_CAST).mapNotNull { type ->
            val discoveryListener = listener()
            runCatching {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                discoveryListener
            }.getOrNull()
        }

        awaitClose {
            listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
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
        fun phoneAddresses(): Set<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { adapter -> adapter.inetAddresses.asSequence() }
                .mapNotNull { it.hostAddress?.substringBefore('%') }
                .toSet()
        }.getOrDefault(emptySet())

        /** `fn` (friendly name) from the cast service attributes, falling back to `md` (model). */
        fun friendlyName(info: NsdServiceInfo): String? {
            val attributes = info.attributes ?: return null
            return listOf("fn", "md")
                .firstNotNullOfOrNull { key -> attributes[key]?.toString(Charsets.UTF_8) }
                ?.takeIf { it.isNotBlank() }
        }
    }
}
