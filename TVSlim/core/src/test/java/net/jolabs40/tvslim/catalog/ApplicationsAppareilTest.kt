package net.jolabs40.tvslim.catalog

import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.device.paquetsInconnus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a phone, preinstalled launcher apps can be disabled one by one; on a TV nothing changes. Data from a Pixel 9a:
 * YouTube and YouTube Music preinstalled, Spotify user-installed.
 */
class ApplicationsAppareilTest {

    private val catalogue = Catalogue(
        categories = listOf(Categorie("streaming", "Streaming"), Categorie(CATEGORIE_APPAREIL, "Applications")),
        entrees = listOf(EntreePaquet("com.google.android.youtube.tv", "YouTube", "", "streaming")),
        proteges = listOf(PaquetProtege("com.android.settings", "Réglages")),
    )

    private val pixel = InfosAppareil(
        marque = "Google",
        modele = "Pixel 9a",
        caracteristiques = "nosdcard",
        fonctions = setOf(InfosAppareil.FONCTION_TACTILE),
    )

    private val tcl = InfosAppareil(
        marque = "TCL",
        modele = "Smart TV Pro",
        caracteristiques = "tv",
        fonctions = setOf(InfosAppareil.FONCTION_LEANBACK),
    )

    private val systeme = mapOf(
        "com.google.android.youtube" to EtatPaquet.ACTIF,
        "com.google.android.apps.youtube.music" to EtatPaquet.DESACTIVE,
        "com.android.settings" to EtatPaquet.ACTIF,
        "com.android.phone" to EtatPaquet.ACTIF,
    )

    private val menu = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.android.settings",
        // User-installed, so not among the system packages: left out.
        "com.spotify.music",
    )

    @Test
    fun `on a phone, preinstalled launcher apps become untested entries`() {
        val vu = catalogue.avecApplicationsDuMenu(pixel, systeme, menu)

        val ajoutees = vu.entrees.filter { it.categorie == CATEGORIE_APPAREIL }
        assertEquals(
            listOf("com.google.android.apps.youtube.music", "com.google.android.youtube"),
            ajoutees.map { it.paquet },
        )
        // Never selected by a profile, and flagged as such.
        assertTrue(ajoutees.none { it.eprouve })
        // The blocklist wins, and a package with no launcher icon (the phone app) is not offered.
        assertFalse(vu.entrees.any { it.paquet == "com.android.settings" || it.paquet == "com.android.phone" })
        assertFalse(vu.entrees.any { it.paquet == "com.spotify.music" })
        // No longer listed as unknown.
        assertFalse(vu.paquetsInconnus(systeme, null).any { it.paquet == "com.google.android.youtube" })
    }

    @Test
    fun `on a TV, nothing changes`() {
        assertSame(catalogue, catalogue.avecApplicationsDuMenu(tcl, systeme, menu))
        // With nothing read yet the brand decides: an unread Pixel passes for a box, so nothing is added.
        assertSame(catalogue, catalogue.avecApplicationsDuMenu(InfosAppareil(marque = "Google"), systeme, menu))
    }

    @Test
    fun `launcher apps are parsed from the query-activities output`() {
        val lignes = listOf(
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true",
            "com.google.android.youtube/com.google.android.apps.youtube.app.WatchWhileActivity",
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false",
            "com.android.settings/.Settings",
            "No activities found",
        )

        assertEquals(setOf("com.google.android.youtube", "com.android.settings"), LecteurDistant.applicationsMenu(lignes))
    }
}
