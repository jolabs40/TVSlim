package net.jolabs40.tvslim.windows.reseau

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket

/** Ce que le balayage sonde — et surtout ce qu'il ne sonde pas. */
class BalayageReseauTest {

    @Test
    fun `un reseau domestique en 24 donne ses 253 autres hotes`() {
        val hotes = hotesVoisins("192.168.2.10", 24)

        assertEquals(253, hotes.size)
        assertEquals("192.168.2.1", hotes.first())
        assertEquals("192.168.2.254", hotes.last())
        assertFalse("l'ordinateur ne se sonde pas lui-même", "192.168.2.10" in hotes)
        assertFalse("ni l'adresse de diffusion", "192.168.2.255" in hotes)
    }

    @Test
    fun `un reseau plus large n'est parcouru que sur la tranche de l'ordinateur`() {
        val hotes = hotesVoisins("10.20.30.40", 16)

        assertEquals(253, hotes.size)
        assertTrue(hotes.all { it.startsWith("10.20.30.") })
    }

    @Test
    fun `un petit sous-reseau reste dans ses bornes`() {
        assertEquals(listOf("192.168.2.130"), hotesVoisins("192.168.2.129", 30))
        assertTrue(hotesVoisins("192.168.2.129", 31).isEmpty())
    }

    @Test
    fun `rien n'est sonde hors du reseau local`() {
        assertTrue(hotesVoisins("8.8.8.8", 24).isEmpty())
        assertTrue(hotesVoisins("127.0.0.1", 8).isEmpty())
        assertTrue(hotesVoisins("169.254.10.20", 16).isEmpty())
        assertTrue(hotesVoisins("pas une adresse", 24).isEmpty())
    }

    @Test
    fun `le balayage ne rend que les hotes dont le port repond`() = runTest {
        val repondent = setOf("192.168.2.135", "192.168.2.193")
        val balayage = BalayageReseau(sonder = { hote, _, _ -> hote in repondent })
        val carte = InterfaceLocale(
            nom = "Ethernet",
            adresse = InetAddress.getByName("192.168.2.10") as Inet4Address,
            prefixe = 24,
        )

        assertEquals(repondent, balayage.balayer(listOf(carte)))
    }

    @Test
    fun `un port qui ecoute se detecte, un port ferme non`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { serveur ->
            assertTrue(portOuvert("127.0.0.1", serveur.localPort, 500))
        }
        val libre = ServerSocket(0).use { it.localPort }
        assertFalse(portOuvert("127.0.0.1", libre, 500))
    }

    @Test
    fun `les adaptateurs virtuels sont ecartes`() {
        listOf(
            "vEthernet (WSL (Hyper-V firewall))",
            "VirtualBox Host-Only Ethernet Adapter",
            "VMware Virtual Ethernet Adapter for VMnet8",
            "Tailscale Tunnel",
            "TAP-Windows Adapter V9",
        ).forEach { assertTrue(it, InterfacesReseau.estVirtuelle(it)) }

        listOf("Intel(R) Wi-Fi 6E AX211 160MHz", "Realtek PCIe GbE Family Controller")
            .forEach { assertFalse(it, InterfacesReseau.estVirtuelle(it)) }
    }
}
