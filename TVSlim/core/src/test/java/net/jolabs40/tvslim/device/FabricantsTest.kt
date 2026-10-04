package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reconnaître le fabricant d'un appareil sur ce qu'il déclare. Les couples marque / fabricant viennent
 * de relevés réels : c'est sur eux qu'une lecture naïve de `ro.product.manufacturer` se tromperait.
 */
class FabricantsTest {

    @Test
    fun `la marque vendue l'emporte sur le sous-traitant`() {
        assertEquals(Fabricant.PHILIPS, Fabricant.identifier(marqueCommerciale = "Philips", fabricant = "TPV"))
        assertEquals(Fabricant.PANASONIC, Fabricant.identifier(marqueCommerciale = "PANASONIC", fabricant = "SCBC"))
        assertEquals(Fabricant.THOMSON, Fabricant.identifier(marqueCommerciale = "Thomson", fabricant = "SkyworthDigital"))
    }

    @Test
    fun `sans marque connue, le fabricant suffit, quelle que soit la casse`() {
        assertEquals(Fabricant.TCL, Fabricant.identifier(marqueCommerciale = "", fabricant = "TCL"))
        assertEquals(Fabricant.GOOGLE, Fabricant.identifier(marqueCommerciale = "google", fabricant = "Google"))
        assertEquals(Fabricant.XIAOMI, Fabricant.identifier(marqueCommerciale = "", fabricant = "xiaomi"))
        assertEquals(Fabricant.PHILIPS, Fabricant.identifier(marqueCommerciale = "", fabricant = "TPV"))
        // Une marque cliente inconnue (VEON) laisse parler le fabricant.
        assertEquals(Fabricant.SKYWORTH, Fabricant.identifier(marqueCommerciale = "VEON", fabricant = "skyworth"))
    }

    @Test
    fun `une marque inconnue n'est jamais devinee`() {
        assertNull(Fabricant.identifier(marqueCommerciale = "Formuler", fabricant = "Formuler"))
        assertNull(Fabricant.identifier(marqueCommerciale = "", fabricant = "SEI Robotics"))
        assertNull(Fabricant.identifier(marqueCommerciale = "", fabricant = ""))
    }

    @Test
    fun `xiaomi fait televiseurs et box, et le modele tranche`() {
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Xiaomi", modele = "MIBOX4").typeAppareil)
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Xiaomi", modele = "Mi TV Stick").typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR, InfosAppareil(marque = "Xiaomi", modele = "MiTV-MOOQ0").typeAppareil)
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "NVIDIA", modele = "SHIELD Android TV").typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR, InfosAppareil(marque = "Inconnue", modele = "X1").typeAppareil)
    }

    @Test
    fun `ce que l'appareil declare l'emporte sur sa marque`() {
        val tactile = setOf(InfosAppareil.FONCTION_TACTILE)
        val leanback = setOf(InfosAppareil.FONCTION_LEANBACK, InfosAppareil.FONCTION_TELEVISION)

        // Relevé le 2026-10-04 : le Pixel 9a déclare « nosdcard » et un écran tactile, sans leanback.
        val pixel = InfosAppareil(marque = "Google", marqueCommerciale = "google", modele = "Pixel 9a",
            caracteristiques = "nosdcard", fonctions = tactile)
        assertEquals(TypeAppareil.TELEPHONE, pixel.typeAppareil)
        assertFalse(pixel.typeAppareil.pourLeCatalogue)
        // Le même Google, en Chromecast, reste une box ; la TCL, un téléviseur.
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Google", modele = "Chromecast", fonctions = leanback).typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR,
            InfosAppareil(marque = "TCL", modele = "Smart TV Pro", caracteristiques = "tv", fonctions = leanback).typeAppareil)
        assertEquals(TypeAppareil.TABLETTE,
            InfosAppareil(marque = "samsung", modele = "SM-X200", caracteristiques = "tablet", fonctions = tactile).typeAppareil)
        // Un Fire TV sans leanback, ou une box inconnue sans écran tactile, restent du côté des téléviseurs.
        assertEquals(TypeAppareil.BOX,
            InfosAppareil(marque = "Amazon", modele = "AFTKA", fonctions = setOf(InfosAppareil.FONCTION_FIRE_TV)).typeAppareil)
        assertEquals(TypeAppareil.TELEVISEUR,
            InfosAppareil(marque = "Formuler", modele = "Z11", caracteristiques = "default", fonctions = emptySet()).typeAppareil)
        // Rien de lu : la marque décide, comme avant.
        assertEquals(TypeAppareil.BOX, InfosAppareil(marque = "Google", modele = "Pixel 9a").typeAppareil)
    }

    @Test
    fun `seules les fonctions qui disent le genre d'appareil sont gardees`() {
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
    fun `le nom retenu porte la marque vendue, et la marque s'y relit`() {
        val philips = InfosAppareil(marque = "TPV", marqueCommerciale = "Philips", modele = "55PUS8807/12")
        assertEquals("Philips 55PUS8807/12", philips.nomAffiche)
        assertEquals(Fabricant.PHILIPS, Fabricant.depuisNom(philips.nomAffiche))

        // Le nom déjà retenu pour la TCL ne change pas : les préférences restent valables.
        val tcl = InfosAppareil(marque = "TCL", marqueCommerciale = "TCL", modele = "Smart TV Pro")
        assertEquals("TCL Smart TV Pro", tcl.nomAffiche)

        assertEquals(Fabricant.NVIDIA, Fabricant.depuisNom("NVIDIA SHIELD Android TV"))
        assertNull(Fabricant.depuisNom("192.168.2.135"))
        assertEquals("Formuler Z10", InfosAppareil(marque = "Formuler", modele = "Z10").nomAffiche)
    }

    @Test
    fun `un modele qui porte deja la marque ne la repete pas`() {
        // La Philips relevée le 2026-10-04 : ro.product.manufacturer TPV, ro.product.model « Philips Google TV TA1 ».
        val philips = InfosAppareil(marque = "TPV", marqueCommerciale = "Philips", modele = "Philips Google TV TA1")
        assertEquals("Philips Google TV TA1", philips.nomAffiche)
        assertEquals(Fabricant.PHILIPS, Fabricant.depuisNom(philips.nomAffiche))
        // Un mot qui commence seulement comme la marque n'est pas la marque.
        assertEquals("TCL TCLink 4K", InfosAppareil(marque = "TCL", modele = "TCLink 4K").nomAffiche)
    }
}
