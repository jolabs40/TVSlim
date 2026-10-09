package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Manufacturer detection. The brand/manufacturer pairs come from real devices, where a naive read of
 * `ro.product.manufacturer` would be wrong.
 */
class FabricantsTest {

    @Test
    fun `the retail brand wins over the contract manufacturer`() {
        assertEquals(Fabricant.PHILIPS, Fabricant.identifier(marqueCommerciale = "Philips", fabricant = "TPV"))
        assertEquals(Fabricant.PANASONIC, Fabricant.identifier(marqueCommerciale = "PANASONIC", fabricant = "SCBC"))
        assertEquals(Fabricant.THOMSON, Fabricant.identifier(marqueCommerciale = "Thomson", fabricant = "SkyworthDigital"))
    }

    @Test
    fun `without a known brand, the manufacturer is enough, whatever its letter case`() {
        assertEquals(Fabricant.TCL, Fabricant.identifier(marqueCommerciale = "", fabricant = "TCL"))
        assertEquals(Fabricant.GOOGLE, Fabricant.identifier(marqueCommerciale = "google", fabricant = "Google"))
        assertEquals(Fabricant.XIAOMI, Fabricant.identifier(marqueCommerciale = "", fabricant = "xiaomi"))
        assertEquals(Fabricant.PHILIPS, Fabricant.identifier(marqueCommerciale = "", fabricant = "TPV"))
        // An unknown client brand (VEON) falls back to the manufacturer.
        assertEquals(Fabricant.SKYWORTH, Fabricant.identifier(marqueCommerciale = "VEON", fabricant = "skyworth"))
    }

    @Test
    fun `an unknown brand is never guessed`() {
        assertNull(Fabricant.identifier(marqueCommerciale = "Formuler", fabricant = "Formuler"))
        assertNull(Fabricant.identifier(marqueCommerciale = "", fabricant = "SEI Robotics"))
        assertNull(Fabricant.identifier(marqueCommerciale = "", fabricant = ""))
    }

    @Test
    fun `xiaomi makes both tvs and boxes, and the model decides`() {
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Xiaomi", modele = "MIBOX4").typeAppareil)
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Xiaomi", modele = "Mi TV Stick").typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR, InfosAppareil(marque = "Xiaomi", modele = "MiTV-MOOQ0").typeAppareil)
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "NVIDIA", modele = "SHIELD Android TV").typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR, InfosAppareil(marque = "Inconnue", modele = "X1").typeAppareil)
    }

    @Test
    fun `what the device declares wins over its brand`() {
        val tactile = setOf(InfosAppareil.FONCTION_TACTILE)
        val leanback = setOf(InfosAppareil.FONCTION_LEANBACK, InfosAppareil.FONCTION_TELEVISION)

        // Read from a Pixel 9a: `nosdcard` and a touchscreen, no leanback.
        val pixel = InfosAppareil(marque = "Google", marqueCommerciale = "google", modele = "Pixel 9a",
            caracteristiques = "nosdcard", fonctions = tactile)
        assertEquals(TypeAppareil.TELEPHONE, pixel.typeAppareil)
        assertFalse(pixel.typeAppareil.pourLeCatalogue)
        // A Google Chromecast is still a box, the TCL a TV.
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Google", modele = "Chromecast", fonctions = leanback).typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR,
            InfosAppareil(marque = "TCL", modele = "Smart TV Pro", caracteristiques = "tv", fonctions = leanback).typeAppareil)
        assertEquals(TypeAppareil.TABLETTE,
            InfosAppareil(marque = "samsung", modele = "SM-X200", caracteristiques = "tablet", fonctions = tactile).typeAppareil)
        // A Fire TV without leanback, or an unknown box without a touchscreen, still counts as a TV device.
        assertEquals(TypeAppareil.BOX,
            InfosAppareil(marque = "Amazon", modele = "AFTKA", fonctions = setOf(InfosAppareil.FONCTION_FIRE_TV)).typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR,
            InfosAppareil(marque = "Formuler", modele = "Z11", caracteristiques = "default", fonctions = emptySet()).typeAppareil)
        // Nothing read: the brand decides.
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Google", modele = "Pixel 9a").typeAppareil)
    }

    @Test
    fun `only the features that tell the device type are kept`() {
        assertEquals(
            setOf(InfosAppareil.FONCTION_LEANBACK, InfosAppareil.FONCTION_TACTILE),
            LecteurDistant.fonctions(
                listOf(
                    "feature:android.software.leanback",
                    "feature:android.hardware.touchscreen",
                    "feature:android.hardware.touchscreen.multitouch",
                    "feature:reqGlEsVersion=0x30002",
                    "feature:android.hardware.wifi",
                ),
            ),
        )
    }

    @Test
    fun `the display name carries the retail brand, which can be read back from it`() {
        val philips = InfosAppareil(marque = "TPV", marqueCommerciale = "Philips", modele = "55PUS8807/12")
        assertEquals("Philips 55PUS8807/12", philips.nomAffiche)
        assertEquals(Fabricant.PHILIPS, Fabricant.depuisNom(philips.nomAffiche))

        // The TCL's name must not change: saved preferences are keyed on it.
        val tcl = InfosAppareil(marque = "TCL", marqueCommerciale = "TCL", modele = "Smart TV Pro")
        assertEquals("TCL Smart TV Pro", tcl.nomAffiche)

        assertEquals(Fabricant.NVIDIA, Fabricant.depuisNom("NVIDIA SHIELD Android TV"))
        assertNull(Fabricant.depuisNom("192.168.2.135"))
        assertEquals("Formuler Z10", InfosAppareil(marque = "Formuler", modele = "Z10").nomAffiche)
    }

    @Test
    fun `a model that already contains the brand does not repeat it`() {
        // Real Philips: ro.product.manufacturer TPV, ro.product.model "Philips Google TV TA1".
        val philips = InfosAppareil(marque = "TPV", marqueCommerciale = "Philips", modele = "Philips Google TV TA1")
        assertEquals("Philips Google TV TA1", philips.nomAffiche)
        assertEquals(Fabricant.PHILIPS, Fabricant.depuisNom(philips.nomAffiche))
        // A word that merely starts with the brand is not the brand.
        assertEquals("TCL TCLink 4K", InfosAppareil(marque = "TCL", modele = "TCLink 4K").nomAffiche)
    }
}
