package net.jolabs40.tvslim.windows.network

import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalInterface(
    val name: String,
    val address: Inet4Address,
    val prefix: Int,
)

object NetworkInterfaces {

    /**
     * Lists interfaces a TV may be reached through: up, private IPv4, not virtual or tunnel. A developer PC often
     * has half a dozen Hyper-V, WSL or VPN adapters; listening on all of them multiplies traffic for nothing.
     */
    fun active(): List<LocalInterface> = runCatching {
        NetworkInterface.networkInterfaces().toList()
            .filter { adapter ->
                adapter.isUp && !adapter.isLoopback && !adapter.isVirtual && !adapter.isPointToPoint &&
                    !isVirtual("${adapter.displayName.orEmpty()} ${adapter.name.orEmpty()}")
            }
            .flatMap { adapter ->
                adapter.interfaceAddresses.mapNotNull { address ->
                    val ipv4 = address.address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress) return@mapNotNull null
                    if (!isOnLocalNetwork(ipv4.hostAddress)) return@mapNotNull null
                    LocalInterface(
                        name = adapter.displayName ?: adapter.name,
                        address = ipv4,
                        prefix = address.networkPrefixLength.toInt(),
                    )
                }
            }
    }.getOrDefault(emptyList())

    /** Virtual machine, VPN and tunnel adapters, where a TV never is. */
    fun isVirtual(name: String): Boolean = VIRTUAL_ADAPTER_WORDS.any { name.contains(it, ignoreCase = true) }

    private val VIRTUAL_ADAPTER_WORDS = listOf(
        "virtual", "vmware", "virtualbox", "hyper-v", "vethernet", "wsl", "docker",
        "vpn", "wireguard", "tailscale", "zerotier", "tap-windows", "tunnel", "teredo",
        "isatap", "loopback", "bluetooth", "npcap", "pseudo",
    )
}
