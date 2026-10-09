package net.jolabs40.tvslim.windows.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.jolabs40.tvslim.windows.adb.DEFAULT_ADB_PORT
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Scans the PC's subnets for devices whose ADB port answers.
 *
 * Many TVs announce nothing over mDNS. A bare TCP connect, closed at once, tells whether the port listens
 * without triggering an authorization prompt, which only appears once the ADB handshake starts.
 *
 * Limited to each interface's /24: 254 parallel probes take one to two seconds.
 */
class NetworkScan(
    private val timeoutMs: Int = 350,
    private val concurrency: Int = 64,
    private val probe: (host: String, port: Int, timeoutMs: Int) -> Boolean = ::isPortOpen,
) {

    suspend fun scan(
        interfaces: List<LocalInterface>,
        port: Int = DEFAULT_ADB_PORT,
    ): Set<String> = coroutineScope {
        val candidates = interfaces
            .flatMap { neighborHosts(it.address.hostAddress, it.prefix) }
            .distinct()
        val limit = Semaphore(concurrency)
        candidates
            .map { host ->
                async(Dispatchers.IO) {
                    limit.withPermit { host.takeIf { probe(it, port, timeoutMs) } }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toSet()
    }
}

/**
 * Lists the other hosts in the subnet of [address]. For networks wider than /24, only the /24 containing the PC
 * is scanned.
 *
 * Returns nothing for public, loopback or link-local addresses.
 */
fun neighborHosts(address: String, prefix: Int): List<String> {
    val bytes = address.split('.').mapNotNull { it.toIntOrNull() }
    if (bytes.size != 4 || !isOnLocalNetwork(address)) return emptyList()
    if (bytes[0] == 127 || (bytes[0] == 169 && bytes[1] == 254)) return emptyList()

    val effective = prefix.coerceIn(24, 32)
    if (effective >= 31) return emptyList()

    val rawValue = bytes.fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() }
    val mask = (0xFFFFFFFFL shl (32 - effective)) and 0xFFFFFFFFL
    val network = rawValue and mask
    val broadcast = network or (mask.inv() and 0xFFFFFFFFL)

    return ((network + 1) until broadcast)
        .filter { it != rawValue }
        .map { v -> "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}" }
}

/** Opens and closes a TCP connection without sending anything. */
internal fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    true
}.getOrDefault(false)
