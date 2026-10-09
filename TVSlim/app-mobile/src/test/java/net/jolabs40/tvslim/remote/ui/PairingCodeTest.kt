package net.jolabs40.tvslim.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a scanned code is allowed to make the app do.
 *
 * The content comes from an image, and a sticker scans as well as a TV screen. It decides where the
 * companion connects and where it writes, and both are checked here.
 *
 * The `tvslim://` form is not covered: it goes through `android.net.Uri`, missing from a plain JVM.
 * It then hits the same filter, which is tested through the other two forms.
 */
class PairingCodeTest {

    // --- Connection targets ---------------------------------------------------------------

    @Test
    fun `private network addresses are accepted`() {
        listOf(
            "192.168.2.135", // the TCL
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "169.254.3.4", // link-local, when DHCP did not answer
            "127.0.0.1", // loopback, for an emulator
        ).forEach { assertTrue(it, isOnLocalNetwork(it)) }
    }

    @Test
    fun `anything that is not a private address is rejected`() {
        listOf(
            "8.8.8.8", // public, perfectly valid
            "172.15.0.1", // just below the private range
            "172.32.0.1", // just above it
            "example.invalid", // a hostname: the TV never advertises one
            "192.168.2", // truncated
            "192.168.2.135.7", // too many octets
            "999.1.1.1", // out of range
            "192.168.2.135 ", // trailing space
            "",
        ).forEach { assertFalse(it, isOnLocalNetwork(it)) }
    }

    @Test
    fun `a bare address gets the default ADB port`() {
        assertEquals(TvAddress("192.168.2.135", 5555), readPairingCode("192.168.2.135"))
    }

    @Test
    fun `an explicit port is kept and surrounding spaces are ignored`() {
        assertEquals(TvAddress("192.168.2.135", 5037), readPairingCode("  192.168.2.135:5037  "))
    }

    @Test
    fun `a host outside the local network yields no address`() {
        assertNull(readPairingCode("example.invalid"))
        assertNull(readPairingCode("8.8.8.8:5555"))
    }

    @Test
    fun `an impossible port yields no address`() {
        assertNull(readPairingCode("192.168.2.135:0"))
        assertNull(readPairingCode("192.168.2.135:70000"))
    }

    // --- File keys ------------------------------------------------------------------------

    @Test
    fun `an IPv4 address gives the same key as before, so existing journals are still found`() {
        // Must match `host.replace('.', '_')` for IPv4, or the journal and measurements of every TV
        // already visited would be lost.
        listOf("192.168.2.135", "192.168.2.193", "192.168.2.153").forEach {
            assertEquals(it.replace('.', '_'), fileKey(it))
        }
    }

    @Test
    fun `a slash cannot open a subfolder`() {
        assertEquals("a_b", fileKey("a/b"))
        assertEquals("a_b", fileKey("a\\b"))
        assertEquals("___etc_passwd", fileKey("../etc/passwd"))
    }
}
