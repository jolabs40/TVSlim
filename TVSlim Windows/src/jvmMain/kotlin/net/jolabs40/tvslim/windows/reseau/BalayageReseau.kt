package net.jolabs40.tvslim.windows.reseau

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
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
class BalayageReseau(
    private val delaiMs: Int = 350,
    private val simultanees: Int = 64,
    private val sonder: (hote: String, port: Int, delaiMs: Int) -> Boolean = ::portOuvert,
) {

    suspend fun balayer(
        interfaces: List<InterfaceLocale>,
        port: Int = PORT_ADB_PAR_DEFAUT,
    ): Set<String> = coroutineScope {
        val candidats = interfaces
            .flatMap { hotesVoisins(it.adresse.hostAddress, it.prefixe) }
            .distinct()
        val limite = Semaphore(simultanees)
        candidats
            .map { hote ->
                async(Dispatchers.IO) {
                    limite.withPermit { hote.takeIf { sonder(it, port, delaiMs) } }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toSet()
    }
}

/**
 * Lists the other hosts in the subnet of [adresse]. For networks wider than /24, only the /24 containing the PC
 * is scanned.
 *
 * Returns nothing for public, loopback or link-local addresses.
 */
fun hotesVoisins(adresse: String, prefixe: Int): List<String> {
    val octets = adresse.split('.').mapNotNull { it.toIntOrNull() }
    if (octets.size != 4 || !estSurLeReseauLocal(adresse)) return emptyList()
    if (octets[0] == 127 || (octets[0] == 169 && octets[1] == 254)) return emptyList()

    val effectif = prefixe.coerceIn(24, 32)
    if (effectif >= 31) return emptyList()

    val valeur = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
    val masque = (0xFFFFFFFFL shl (32 - effectif)) and 0xFFFFFFFFL
    val reseau = valeur and masque
    val diffusion = reseau or (masque.inv() and 0xFFFFFFFFL)

    return ((reseau + 1) until diffusion)
        .filter { it != valeur }
        .map { v -> "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}" }
}

/** Opens and closes a TCP connection without sending anything. */
internal fun portOuvert(hote: String, port: Int, delaiMs: Int): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(hote, port), delaiMs) }
    true
}.getOrDefault(false)
