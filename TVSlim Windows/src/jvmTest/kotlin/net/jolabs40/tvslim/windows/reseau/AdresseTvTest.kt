package net.jolabs40.tvslim.windows.reseau

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vers qui l'application se connecte, et où elle écrit. Reprend les cas du compagnon Android
 * (`CodeAppairageTest`), plus la forme `tvslim://` — rejouable ici, `java.net.URI` étant sur
 * toutes les JVM — et la saisie d'un bloc propre au bureau.
 */
class AdresseTvTest {

    // --- Vers qui l'on se connecte --------------------------------------------------------

    @Test
    fun `les adresses des reseaux prives sont acceptees`() {
        listOf(
            "192.168.2.135", // la TCL
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "169.254.3.4", // lien-local, quand le DHCP n'a pas répondu
            "127.0.0.1", // boucle locale, pour un émulateur
        ).forEach { assertTrue(it, estSurLeReseauLocal(it)) }
    }

    @Test
    fun `tout ce qui n'est pas une adresse privee est refuse`() {
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
    fun `le lien de l'application TV donne l'adresse et le port`() {
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
    fun `un lien vers un hote exterieur ne donne rien`() {
        assertNull(lireCodeAppairage("tvslim://connect?host=exemple.invalide&port=5555"))
        assertNull(lireCodeAppairage("tvslim://connect?host=8.8.8.8"))
        assertNull(lireCodeAppairage("tvslim://connect"))
    }

    @Test
    fun `une adresse seule prend le port ADB par defaut`() {
        assertEquals(AdresseTv("192.168.2.135", 5555), lireCodeAppairage("192.168.2.135"))
    }

    @Test
    fun `un port impossible ne donne aucune adresse`() {
        assertNull(lireCodeAppairage("192.168.2.135:0"))
        assertNull(lireCodeAppairage("192.168.2.135:70000"))
    }

    // --- Saisie manuelle --------------------------------------------------------------------

    @Test
    fun `une saisie d'un bloc est decoupee en hote et port`() {
        assertEquals(AdresseTv("192.168.2.135", 5037), interpreterSaisie(" 192.168.2.135:5037 ", "5555"))
    }

    @Test
    fun `sans port colle, le champ port fait foi`() {
        assertEquals(AdresseTv("192.168.2.135", 5556), interpreterSaisie("192.168.2.135", "5556"))
        assertEquals(AdresseTv("192.168.2.135", 5555), interpreterSaisie("192.168.2.135", ""))
    }

    @Test
    fun `une adresse tapee n'est pas filtree, un nom d'hote reste possible`() {
        assertEquals(AdresseTv("tv-salon.local", 5555), interpreterSaisie("tv-salon.local", "5555"))
    }

    @Test
    fun `une saisie vide ou un port colle invalide ne donne rien`() {
        assertNull(interpreterSaisie("   ", "5555"))
        assertNull(interpreterSaisie("192.168.2.135:", "5555"))
        assertNull(interpreterSaisie(":5555", "5555"))
    }

    // --- Où l'on écrit ------------------------------------------------------------------------

    @Test
    fun `une adresse IPv4 donne la meme cle que sur le telephone`() {
        listOf("192.168.2.135", "192.168.2.193", "192.168.2.153").forEach {
            assertEquals(it.replace('.', '_'), cleDeFichier(it))
        }
    }

    @Test
    fun `une barre ne peut pas ouvrir de sous-dossier`() {
        assertEquals("a_b", cleDeFichier("a/b"))
        assertEquals("a_b", cleDeFichier("a\\b"))
        assertEquals("___etc_passwd", cleDeFichier("../etc/passwd"))
    }
}
