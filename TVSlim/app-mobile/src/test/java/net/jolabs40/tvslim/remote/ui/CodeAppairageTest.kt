package net.jolabs40.tvslim.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce qu'un code scanné a le droit de faire faire à l'application.
 *
 * Le contenu vient d'une image, et une image se fabrique : un autocollant se scanne aussi bien
 * qu'un écran de téléviseur. Deux portes s'ouvraient là — vers qui le compagnon se connecte, et
 * où il écrit — et ce sont elles qui sont vérifiées ici.
 *
 * La forme `tvslim://…` n'est pas rejouée : elle passe par `android.net.Uri`, absent d'une JVM
 * nue. Le filtre qu'elle traverse ensuite est le même, et il est éprouvé sur les deux autres.
 */
class CodeAppairageTest {

    // --- Vers qui l'on se connecte --------------------------------------------------------

    @Test
    fun `les adresses des reseaux prives sont acceptees`() {
        listOf(
            "192.168.2.135", // la TCL
            "192.168.1.1",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "169.254.3.4", // lien-local, quand le DHCP n'a pas repondu
            "127.0.0.1", // boucle locale, pour un emulateur
        ).forEach { assertTrue(it, estSurLeReseauLocal(it)) }
    }

    @Test
    fun `tout ce qui n'est pas une adresse privee est refuse`() {
        listOf(
            "8.8.8.8", // une adresse publique, parfaitement valide
            "172.15.0.1", // juste sous la plage privee
            "172.32.0.1", // juste au-dessus
            "exemple.invalide", // un nom : le televiseur n'en publie jamais
            "192.168.2", // tronquee
            "192.168.2.135.7", // trop d'octets
            "999.1.1.1", // hors bornes
            "192.168.2.135 ", // une espace de trop
            "",
        ).forEach { assertFalse(it, estSurLeReseauLocal(it)) }
    }

    @Test
    fun `une adresse seule prend le port ADB par defaut`() {
        assertEquals(AdresseTv("192.168.2.135", 5555), lireCodeAppairage("192.168.2.135"))
    }

    @Test
    fun `un port explicite est retenu, les espaces autour sont ignores`() {
        assertEquals(AdresseTv("192.168.2.135", 5037), lireCodeAppairage("  192.168.2.135:5037  "))
    }

    @Test
    fun `un hote hors du reseau local ne donne aucune adresse`() {
        assertNull(lireCodeAppairage("exemple.invalide"))
        assertNull(lireCodeAppairage("8.8.8.8:5555"))
    }

    @Test
    fun `un port impossible ne donne aucune adresse`() {
        assertNull(lireCodeAppairage("192.168.2.135:0"))
        assertNull(lireCodeAppairage("192.168.2.135:70000"))
    }

    // --- Ou l'on ecrit --------------------------------------------------------------------

    @Test
    fun `une adresse IPv4 donne la meme cle qu'avant, les journaux restent retrouves`() {
        // L'ancienne regle etait `hote.replace('.', '_')`. Elle doit rendre le meme resultat,
        // sans quoi le journal et les mesures de chaque televiseur deja visite seraient perdus.
        listOf("192.168.2.135", "192.168.2.193", "192.168.2.153").forEach {
            assertEquals(it.replace('.', '_'), cleDeFichier(it))
        }
    }

    @Test
    fun `une barre ne peut plus ouvrir de sous-dossier`() {
        assertEquals("a_b", cleDeFichier("a/b"))
        assertEquals("a_b", cleDeFichier("a\\b"))
        assertEquals("___etc_passwd", cleDeFichier("../etc/passwd"))
    }
}
