package net.jolabs40.tvslim.windows.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

/** What the network scan probes, and above all what it does not. */
class NetworkScanTest {

    @Test
    fun `a home network with prefix 24 yields its 253 other hosts`() {
        val hosts = neighborHosts("192.168.2.10", 24)

        assertEquals(253, hosts.size)
        assertEquals("192.168.2.1", hosts.first())
        assertEquals("192.168.2.254", hosts.last())
        assertFalse("the computer does not probe itself", "192.168.2.10" in hosts)
        assertFalse("nor the broadcast address", "192.168.2.255" in hosts)
    }

    @Test
    fun `a wider network is only scanned within the computer's own 24-bit block`() {
        val hosts = neighborHosts("10.20.30.40", 16)

        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("10.20.30.") })
    }

    @Test
    fun `a small subnet stays within its bounds`() {
        assertEquals(listOf("192.168.2.130"), neighborHosts("192.168.2.129", 30))
        assertTrue(neighborHosts("192.168.2.129", 31).isEmpty())
    }

    @Test
    fun `nothing outside the local network is probed`() {
        assertTrue(neighborHosts("8.8.8.8", 24).isEmpty())
        assertTrue(neighborHosts("127.0.0.1", 8).isEmpty())
        assertTrue(neighborHosts("169.254.10.20", 16).isEmpty())
        assertTrue(neighborHosts("not an address", 24).isEmpty())
    }

    @Test
    fun `the scan only returns hosts whose port answers`() = runTest {
        val responding = setOf("192.168.2.135", "192.168.2.193")
        val scanner = NetworkScan(probe = { host, _, _ -> host in responding })
        val adapter = LocalInterface(
            name = "Ethernet",
            address = InetAddress.getByName("192.168.2.10") as Inet4Address,
            prefix = 24,
        )

        assertEquals(responding, scanner.scan(listOf(adapter)))
    }

    @Test
    fun `a listening port is detected, a closed one is not`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            assertTrue(isPortOpen("127.0.0.1", server.localPort, 500))
        }
        val free = ServerSocket(0).use { it.localPort }
        assertFalse(isPortOpen("127.0.0.1", free, 500))
    }

    @Test
    fun `virtual adapters are skipped`() {
        listOf(
            "vEthernet (WSL (Hyper-V firewall))",
            "VirtualBox Host-Only Ethernet Adapter",
            "VMware Virtual Ethernet Adapter for VMnet8",
            "Tailscale Tunnel",
            "TAP-Windows Adapter V9",
        ).forEach { assertTrue(it, NetworkInterfaces.isVirtual(it)) }

        listOf("Intel(R) Wi-Fi 6E AX211 160MHz", "Realtek PCIe GbE Family Controller")
            .forEach { assertFalse(it, NetworkInterfaces.isVirtual(it)) }
    }
}
