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
 * Cherche, sur le sous-réseau de l'ordinateur, les appareils dont le port ADB répond.
 *
 * Beaucoup de téléviseurs n'annoncent rien en mDNS : sans ce balayage, il faudrait aller lire leur
 * adresse IP dans les paramètres. Une simple ouverture de connexion TCP, aussitôt refermée, suffit
 * à savoir si le port écoute — elle ne déclenche aucune demande d'autorisation sur le téléviseur,
 * qui n'en affiche qu'une fois le dialogue ADB engagé.
 *
 * Borné à la tranche /24 de chaque carte : 254 sondes, en parallèle, tiennent en une à deux
 * secondes et suffisent à un réseau domestique.
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
 * Les autres hôtes du sous-réseau de [adresse]. Au-delà d'un /24 — un /16 d'entreprise, par
 * exemple — seule la tranche /24 qui contient l'ordinateur est parcourue.
 *
 * Rien pour une adresse publique, de boucle locale ou de lien-local : un téléviseur domestique
 * n'y est pas, et on ne sonde pas ce qu'on n'a aucune raison de sonder.
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

/** Le port écoute-t-il ? Une connexion ouverte puis refermée, sans rien envoyer. */
internal fun portOuvert(hote: String, port: Int, delaiMs: Int): Boolean = runCatching {
    Socket().use { it.connect(InetSocketAddress(hote, port), delaiMs) }
    true
}.getOrDefault(false)
