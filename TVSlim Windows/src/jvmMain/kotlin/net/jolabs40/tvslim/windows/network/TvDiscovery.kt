package net.jolabs40.tvslim.windows.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.windows.adb.DEFAULT_ADB_PORT
import net.jolabs40.tvslim.windows.tools.AppLog
import net.jolabs40.tvslim.windows.tools.detail
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    /** User-given device name, when one can be found. */
    val friendlyName: String? = null,
    /**
     * Android 11+ wireless debugging, which uses TLS that the embedded ADB client does not support. Still listed
     * so the UI can say what to enable instead.
     */
    val wireless: Boolean = false,
) {
    val label: String get() = friendlyName ?: host
}

/** Devices found so far, and whether a first full scan has completed. */
data class DiscoveryResult(
    val devices: List<DiscoveredDevice> = emptyList(),
    val firstRoundDone: Boolean = false,
)

/**
 * Finds TVs reachable over ADB.
 *
 * Two sources, merged by address:
 *  - mDNS, as on the phone: `_adb._tcp` (network debugging), `_adb-tls-connect._tcp` (wireless debugging) and
 *    `_googlecast._tcp`, which carries the user-given name (e.g. "Living room"), matched to the others by IP;
 *  - a scan of port 5555 ([NetworkScan]), since many TVs announce nothing.
 */
class TvDiscovery(
    private val scanner: NetworkScan = NetworkScan(),
    private val interfaces: () -> List<LocalInterface> = NetworkInterfaces::active,
) {

    fun stream(): Flow<DiscoveryResult> = channelFlow {
        val announcements = ConcurrentHashMap<String, DiscoveredDevice>()
        val namesByHost = ConcurrentHashMap<String, String>()
        val scanned = AtomicReference<Set<String>>(emptySet())
        val roundDone = AtomicBoolean(false)

        fun publish() {
            val fromAnnouncement = announcements.values.toList()
            val alreadySeen = fromAnnouncement.map { "${it.host}:${it.port}" }.toSet()
            val fromScan = scanned.get()
                .filterNot { "$it:$DEFAULT_ADB_PORT" in alreadySeen }
                .map { DiscoveredDevice(name = it, host = it, port = DEFAULT_ADB_PORT) }
            val devices = (fromAnnouncement + fromScan)
                .map { it.copy(friendlyName = it.friendlyName ?: namesByHost[it.host]) }
                .sortedWith(compareBy<DiscoveredDevice>({ it.wireless }, { it.label }))
            trySend(DiscoveryResult(devices, roundDone.get()))
        }

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                event.dns.requestServiceInfo(event.type, event.name)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                if (announcements.values.removeIf { it.name == event.name }) publish()
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info ?: return
                val address = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                if (event.type.startsWith(TYPE_CAST)) {
                    friendlyName(info)?.let { namesByHost[address] = it }
                } else {
                    announcements["$address:${info.port}"] = DiscoveredDevice(
                        name = info.name,
                        host = address,
                        port = info.port,
                        wireless = event.type.startsWith(TYPE_ADB_TLS),
                    )
                }
                publish()
            }
        }

        val locales = interfaces()
        val instances = locales.mapNotNull { adapter ->
            runCatching {
                JmDNS.create(adapter.address, "tvslim").also { dns ->
                    TYPES.forEach { type -> dns.addServiceListener(type, listener) }
                }
            }.onFailure {
                AppLog.warn(TAG, "mDNS unavailable" + detail(adapter.name), it)
            }.getOrNull()
        }

        launch {
            while (isActive) {
                scanned.set(scanner.scan(locales))
                roundDone.set(true)
                publish()
                delay(SCAN_INTERVAL_MS)
            }
        }

        awaitClose {
            // Closing JmDNS sends goodbye messages and takes 1 to 2 s; this runs on Dispatchers.IO (flowOn), not
            // on the UI thread.
            instances.forEach { runCatching { it.close() } }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "Discovery"

        /** Classic network debugging, as on TVs listening on port 5555. */
        const val TYPE_ADB = "_adb._tcp.local."

        /** Android 11+ wireless debugging, once the device is paired. */
        const val TYPE_ADB_TLS = "_adb-tls-connect._tcp.local."

        /** Built-in Chromecast, which carries the user-given device name. */
        const val TYPE_CAST = "_googlecast._tcp.local."

        val TYPES = listOf(TYPE_ADB, TYPE_ADB_TLS, TYPE_CAST)

        /** Rescan period, so a TV switched on later eventually shows up. */
        const val SCAN_INTERVAL_MS = 20_000L

        /** `fn` (friendly name) from the cast TXT record, else `md` (model). */
        fun friendlyName(info: ServiceInfo): String? =
            listOf("fn", "md")
                .firstNotNullOfOrNull { key -> info.getPropertyString(key) }
                ?.takeIf { it.isNotBlank() }
    }
}
