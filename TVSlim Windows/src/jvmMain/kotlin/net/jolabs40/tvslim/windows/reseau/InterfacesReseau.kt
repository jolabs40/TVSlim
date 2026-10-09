package net.jolabs40.tvslim.windows.reseau

import java.net.Inet4Address
import java.net.NetworkInterface

data class InterfaceLocale(
    val nom: String,
    val adresse: Inet4Address,
    val prefixe: Int,
)

object InterfacesReseau {

    /**
     * Lists interfaces a TV may be reached through: up, private IPv4, not virtual or tunnel. A developer PC often
     * has half a dozen Hyper-V, WSL or VPN adapters; listening on all of them multiplies traffic for nothing.
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

    /** Virtual machine, VPN and tunnel adapters, where a TV never is. */
    fun estVirtuelle(nom: String): Boolean = MOTS_VIRTUELS.any { nom.contains(it, ignoreCase = true) }

    private val MOTS_VIRTUELS = listOf(
        "virtual", "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
        "vpn", "wireguard", "tailscale", "zerotier", "tap-windows", "tunnel", "teredo",
        "isatap", "loopback", "bluetooth", "npcap", "pseudo",
    )
}
