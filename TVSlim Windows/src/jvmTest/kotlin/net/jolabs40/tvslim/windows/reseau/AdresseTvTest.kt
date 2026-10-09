package net.jolabs40.tvslim.windows.reseau

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which hosts the app connects to, and where it writes. Mirrors the Android companion's `CodeAppairageTest`, plus
 * the `tvslim://` form (testable here since `java.net.URI` is on every JVM) and the desktop's single-field input.
 */
class AdresseTvTest {

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
        ).forEach { assertTrue(it, estSurLeReseauLocal(it)) }
    }

    @Test
    fun `anything that is not a private address is rejected`() {
        listOf(
            "8.8.8.8",
            "172.15.0.1",
            "172.32.0.1",
            "exemple.invalide",
            "192.168.2",
            "192.168.2.135.7",
            "999.1.1.1",
            "192.168.2.135 ",
            "",
        ).forEach { assertFalse(it, estSurLeReseauLocal(it)) }
    }

    @Test
    fun `the TV app link yields the address and port`() {
        assertEquals(
            AdresseTv("192.168.2.135", 5555),
            lireCodeAppairage("tvslim://connect?host=192.168.2.135&port=5555"),
        )
        assertEquals(
            AdresseTv("192.168.2.193", 5037),
            lireCodeAppairage("  tvslim://connect?port=5037&host=192.168.2.193 "),
        )
    }

    @Test
    fun `a link to an outside host yields nothing`() {
        assertNull(lireCodeAppairage("tvslim://connect?host=exemple.invalide&port=5555"))
        assertNull(lireCodeAppairage("tvslim://connect?host=8.8.8.8"))
        assertNull(lireCodeAppairage("tvslim://connect"))
    }

    @Test
    fun `a bare address gets the default ADB port`() {
        assertEquals(AdresseTv("192.168.2.135", 5555), lireCodeAppairage("192.168.2.135"))
    }

    @Test
    fun `an impossible port yields no address`() {
        assertNull(lireCodeAppairage("192.168.2.135:0"))
        assertNull(lireCodeAppairage("192.168.2.135:70000"))
    }

    // --- Manual input -----------------------------------------------------------------------

    @Test
    fun `a single-field input is split into host and port`() {
        assertEquals(AdresseTv("192.168.2.135", 5037), interpreterSaisie(" 192.168.2.135:5037 ", "5555"))
    }

    @Test
    fun `without an attached port, the port field wins`() {
        assertEquals(AdresseTv("192.168.2.135", 5556), interpreterSaisie("192.168.2.135", "5556"))
        assertEquals(AdresseTv("192.168.2.135", 5555), interpreterSaisie("192.168.2.135", ""))
    }

    @Test
    fun `a typed address is not filtered, so a hostname still works`() {
        assertEquals(AdresseTv("tv-salon.local", 5555), interpreterSaisie("tv-salon.local", "5555"))
    }

    @Test
    fun `blank input or an invalid attached port yields nothing`() {
        assertNull(interpreterSaisie("   ", "5555"))
        assertNull(interpreterSaisie("192.168.2.135:", "5555"))
        assertNull(interpreterSaisie(":5555", "5555"))
    }

    // --- File keys ----------------------------------------------------------------------------

    @Test
    fun `an IPv4 address gives the same key as on the phone`() {
        listOf("192.168.2.135", "192.168.2.193", "192.168.2.153").forEach {
            assertEquals(it.replace('.', '_'), cleDeFichier(it))
        }
    }

    @Test
    fun `a slash cannot open a subfolder`() {
        assertEquals("a_b", cleDeFichier("a/b"))
        assertEquals("a_b", cleDeFichier("a\\b"))
        assertEquals("___etc_passwd", cleDeFichier("../etc/passwd"))
    }
}
