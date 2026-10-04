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
 * Sur un téléphone, les applications préinstallées du menu deviennent désactivables une à une ; sur un téléviseur,
 * rien ne change. Relevé du Pixel 9a le 2026-10-04 : YouTube, YouTube Music, préinstallés ; Spotify, installé.
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
        // Installé par la personne : absent des paquets système, il ne vient pas.
        "com.spotify.music",
    )

    @Test
    fun `sur un telephone, les applications preinstallees du menu deviennent des entrees non eprouvees`() {
        val vu = catalogue.avecApplicationsDuMenu(pixel, systeme, menu)

        val ajoutees = vu.entrees.filter { it.categorie == CATEGORIE_APPAREIL }
        assertEquals(
            listOf("com.google.android.apps.youtube.music", "com.google.android.youtube"),
            ajoutees.map { it.paquet },
        )
        // Jamais cochées par un profil, et marquées comme telles.
        assertTrue(ajoutees.none { it.eprouve })
        // La liste noire l'emporte, et un service sans icône — le téléphone — n'est pas proposé.
        assertFalse(vu.entrees.any { it.paquet == "com.android.settings" || it.paquet == "com.android.phone" })
        assertFalse(vu.entrees.any { it.paquet == "com.spotify.music" })
        // Elles ne sont plus parmi les inconnus.
        assertFalse(vu.paquetsInconnus(systeme, null).any { it.paquet == "com.google.android.youtube" })
    }

    @Test
    fun `sur un televiseur, rien ne change`() {
        assertSame(catalogue, catalogue.avecApplicationsDuMenu(tcl, systeme, menu))
        // Rien de lu, la marque décide : un Pixel non encore lu passe pour une box, donc rien n'est ajouté.
        assertSame(catalogue, catalogue.avecApplicationsDuMenu(InfosAppareil(marque = "Google"), systeme, menu))
    }

    @Test
    fun `le menu se lit dans la reponse de query-activities`() {
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
