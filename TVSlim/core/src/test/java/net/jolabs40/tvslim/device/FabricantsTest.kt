package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
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
}
