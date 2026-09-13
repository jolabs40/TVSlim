package net.jolabs40.tvslim.windows.reseau

import java.net.Inet4Address
import java.net.NetworkInterface

/** Une carte réseau de l'ordinateur, réduite à ce qui sert à chercher un téléviseur. */
data class InterfaceLocale(
    val nom: String,
    val adresse: Inet4Address,
    val prefixe: Int,
)

object InterfacesReseau {

    /**
     * Les cartes par lesquelles un téléviseur peut se trouver : actives, en IPv4 privée, et ni
     * virtuelles ni tunnels. Un PC de développeur porte volontiers une demi-douzaine d'adaptateurs
     * Hyper-V, WSL ou VPN — les écouter tous multiplierait les annonces sans rien trouver de plus.
     */
    fun actives(): List<InterfaceLocale> = runCatching {
        NetworkInterface.networkInterfaces().toList()
            .filter { carte ->
                carte.isUp && !carte.isLoopback && !carte.isVirtual && !carte.isPointToPoint &&
                    !estVirtuelle("${carte.displayName.orEmpty()} ${carte.name.orEmpty()}")
            }
            .flatMap { carte ->
                carte.interfaceAddresses.mapNotNull { adresse ->
                    val ipv4 = adresse.address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) return@mapNotNull null
                    if (!estSurLeReseauLocal(ipv4.hostAddress)) return@mapNotNull null
                    InterfaceLocale(
                        nom = carte.displayName ?: carte.name,
                        adresse = ipv4,
                        prefixe = adresse.networkPrefixLength.toInt(),
                    )
                }
            }
    }.getOrDefault(emptyList())

    /** Adaptateurs de machines virtuelles, de VPN et de tunnels : un téléviseur n'y est jamais. */
    fun estVirtuelle(nom: String): Boolean = MOTS_VIRTUELS.any { nom.contains(it, ignoreCase = true) }

    private val MOTS_VIRTUELS = listOf(
        "virtual", "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
        "vpn", "wireguard", "tailscale", "zerotier", "tap-windows", "tunnel", "teredo",
        "isatap", "loopback", "bluetooth", "npcap", "pseudo",
    )
}
