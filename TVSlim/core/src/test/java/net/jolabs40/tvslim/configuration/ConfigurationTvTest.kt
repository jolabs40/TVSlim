package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.LauncherConnu
import net.jolabs40.tvslim.catalog.LauncherRecommande
import net.jolabs40.tvslim.catalog.PaquetProtege
import net.jolabs40.tvslim.device.AccueilUsine
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LauncherInstalle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Sauvegarder puis réinjecter : le fichier doit se relire tel quel, refuser ce qui n'en est pas un,
 * et le plan ne promettre que ce que le téléviseur peut réellement recevoir.
 */
class ConfigurationTvTest {

    private fun entree(paquet: String, ordre: Int = 100, accueil: Boolean = false) = EntreePaquet(
        paquet = paquet,
        nom = "Nom de $paquet",
        description = "",
        categorie = "test",
        ordre = ordre,
        requiertLauncherTiers = accueil,
    )

    private val catalogue = Catalogue(
        entrees = listOf(
            entree("com.tcl.pub"),
            entree("com.tcl.demo"),
            entree("com.tcl.absent"),
            entree(SETUPWRAITH, ordre = 1, accueil = true),
            entree(LAUNCHERX, ordre = 2, accueil = true),
        ),
        proteges = listOf(PaquetProtege("com.android.location.fused", "Boucle de redémarrage.")),
        launchers = listOf(
            LauncherRecommande(
                paquet = STARTLIGHT,
                nom = "Startlight Launcher",
                description = "",
                id = "startlight",
                variantes = listOf(STARTLIGHT_DEBUG),
            ),
        ),
        launchersConnus = listOf(LauncherConnu("projectivy", "Projectivy Launcher", listOf(PROJECTIVY))),
    )

    private fun launcher(paquet: String) = LauncherInstalle(paquet, paquet, "$paquet/.Accueil")

    /** La TCL de l'utilisateur : Startlight de développement en accueil, Google TV coupé. */
    private val tcl = InfosAppareil(
        marque = "TCL",
        modele = "Smart TV Pro",
        versionAndroid = "14",
        accueilActuel = STARTLIGHT_DEBUG,
        composantAccueil = "$STARTLIGHT_DEBUG/.Accueil",
        launchersTiers = listOf(launcher(PROJECTIVY), launcher(STARTLIGHT_DEBUG)),
        accueilsUsine = listOf(AccueilUsine(LAUNCHERX, "$LAUNCHERX/.home.HomeActivity", actif = false)),
    )

    private fun sauvegarde(
        desactives: List<String> = emptyList(),
        actifs: List<String> = emptyList(),
        accueil: AccueilSauvegarde? = null,
    ) = ConfigurationTv(
        application = ConfigurationTv.APPLICATION,
        format = ConfigurationTv.FORMAT,
        sauvegardeLe = 0,
        accueil = accueil,
        desactives = desactives,
        actifs = actifs,
    )

    // --- Le fichier ----------------------------------------------------------------------------

    @Test
    fun `la sauvegarde retient l'etat des paquets du catalogue et l'accueil en place`() {
        val configuration = catalogue.configurationDe(
            infos = tcl,
            etats = mapOf(
                "com.tcl.pub" to EtatPaquet.DESACTIVE,
                "com.tcl.demo" to EtatPaquet.ACTIF,
                "com.tcl.absent" to EtatPaquet.ABSENT,
            ),
            maintenant = 1_789_300_000_000,
        )

        assertEquals(listOf("com.tcl.pub"), configuration.desactives)
        assertEquals(listOf("com.tcl.demo"), configuration.actifs)
        assertEquals(STARTLIGHT_DEBUG, configuration.accueil?.paquet)
        assertEquals("$STARTLIGHT_DEBUG/.Accueil", configuration.accueil?.composant)
        assertEquals("Startlight Launcher", configuration.accueil?.nom)
        assertEquals("TCL Smart TV Pro", configuration.appareil.nom)
        assertEquals(1_789_300_000_000, configuration.sauvegardeLe)
    }

    @Test
    fun `le selecteur d'Android n'est pas un accueil a retenir`() {
        val sansChoix = tcl.copy(accueilActuel = "android", composantAccueil = "android/.ResolverActivity")

        assertNull(catalogue.configurationDe(sansChoix, emptyMap()).accueil)
    }

    @Test
    fun `une configuration ecrite se relit a l'identique`() {
        val configuration = catalogue.configurationDe(tcl, mapOf("com.tcl.pub" to EtatPaquet.DESACTIVE), 42)

        assertEquals(configuration, FichierConfiguration.lire(FichierConfiguration.ecrire(configuration)))
    }

    @Test
    fun `un fichier qui n'est pas une configuration TV Slim est refuse`() {
        listOf(
            "",
            "pas du JSON",
            "{}",
            """{"a": 1}""",
            """{"application": "Autre chose", "format": 1, "sauvegardeLe": 0}""",
            """{"application": "TV Slim", "format": 99, "sauvegardeLe": 0}""",
        ).forEach { texte ->
            assertNull("Accepté à tort : $texte", FichierConfiguration.lire(texte))
        }
    }

    @Test
    fun `un champ ajoute par une version future ne rend pas le fichier illisible`() {
        val texte = """{"application": "TV Slim", "format": 1, "sauvegardeLe": 0, "reglages": ["x"]}"""

        assertNotNull(FichierConfiguration.lire(texte))
    }

    @Test
    fun `le nom propose dit l'appareil et le jour`() {
        val jour = LocalDate.of(2026, 9, 13)
        val philips = InfosAppareil(marque = "TPV", marqueCommerciale = "Philips", modele = "55PUS8807/12")

        assertEquals("TVSlim-Philips-55PUS8807-12-2026-09-13.json", FichierConfiguration.nomPropose(philips, jour))
        assertEquals("TVSlim-televiseur-2026-09-13.json", FichierConfiguration.nomPropose(InfosAppareil.VIDE, jour))
    }

    // --- Le plan -------------------------------------------------------------------------------

    @Test
    fun `le plan ne retient que les ecarts, dans les deux sens`() {
        val plan = sauvegarde(
            desactives = listOf("com.tcl.pub", "com.tcl.demo"),
            actifs = listOf("com.tcl.absent", "com.retire.du.catalogue", SETUPWRAITH),
        ).planifier(
            catalogue = catalogue,
            etats = mapOf(
                "com.tcl.pub" to EtatPaquet.ACTIF,
                "com.tcl.demo" to EtatPaquet.DESACTIVE,
                "com.tcl.absent" to EtatPaquet.ABSENT,
                SETUPWRAITH to EtatPaquet.DESACTIVE,
            ),
            infos = tcl,
        )

        assertEquals(listOf("com.tcl.pub"), plan.aDesactiver.map { it.paquet })
        assertEquals(listOf(SETUPWRAITH), plan.aReactiver.map { it.paquet })
        assertEquals(listOf("com.tcl.absent", "com.retire.du.catalogue"), plan.ignores)
        assertEquals(2, plan.nombreActions)
    }

    @Test
    fun `un paquet protege n'est jamais promis a la desactivation`() {
        val avecProtege = catalogue.copy(entrees = catalogue.entrees + entree("com.android.location.fused"))

        val plan = sauvegarde(desactives = listOf("com.android.location.fused"))
            .planifier(avecProtege, mapOf("com.android.location.fused" to EtatPaquet.ACTIF), tcl)

        assertTrue(plan.aDesactiver.isEmpty())
        assertTrue(plan.rienAFaire)
    }

    @Test
    fun `un accueil installe se designe par son composant`() {
        val plan = sauvegarde(accueil = AccueilSauvegarde(PROJECTIVY, nom = "Projectivy Launcher"))
            .planifier(catalogue, emptyMap(), tcl)

        assertEquals(ChangementAccueil(PROJECTIVY, "Projectivy Launcher", "$PROJECTIVY/.Accueil"), plan.accueil)
        assertTrue(plan.accueil!!.possible)
    }

    @Test
    fun `une autre version du meme launcher fait l'affaire`() {
        // Sauvegardé sur un téléviseur qui avait la version publiée ; celui-ci n'a que celle de développement.
        val ailleurs = tcl.copy(accueilActuel = PROJECTIVY, composantAccueil = "$PROJECTIVY/.Accueil")

        val plan = sauvegarde(accueil = AccueilSauvegarde(STARTLIGHT, nom = "Startlight Launcher"))
            .planifier(catalogue, emptyMap(), ailleurs)

        assertEquals(STARTLIGHT_DEBUG, plan.accueil?.paquet)
        assertTrue(plan.accueil!!.possible)
    }

    @Test
    fun `l'accueil deja en place, fut-ce une autre version, ne coute rien`() {
        val plan = sauvegarde(accueil = AccueilSauvegarde(STARTLIGHT)).planifier(catalogue, emptyMap(), tcl)

        assertNull(plan.accueil)
        assertTrue(plan.rienAFaire)
    }

    @Test
    fun `un launcher absent garde son nom mais ne se designe pas`() {
        val sansProjectivy = tcl.copy(launchersTiers = listOf(launcher(STARTLIGHT_DEBUG)))

        val plan = sauvegarde(accueil = AccueilSauvegarde(PROJECTIVY, nom = "Projectivy Launcher"))
            .planifier(catalogue, emptyMap(), sansProjectivy)

        assertEquals("Projectivy Launcher", plan.accueil?.nom)
        assertFalse(plan.accueil!!.possible)
        assertEquals(0, plan.nombreActions)
    }

    @Test
    fun `un accueil d'usine desactive se retrouve pour etre rendu`() {
        val plan = sauvegarde(actifs = listOf(LAUNCHERX), accueil = AccueilSauvegarde(LAUNCHERX))
            .planifier(catalogue, mapOf(LAUNCHERX to EtatPaquet.DESACTIVE), tcl)

        assertEquals(listOf(LAUNCHERX), plan.aReactiver.map { it.paquet })
        assertEquals("$LAUNCHERX/.home.HomeActivity", plan.accueil?.composant)
        assertEquals(2, plan.nombreActions)
    }

    private companion object {
        const val STARTLIGHT = "net.jolabs40.startlight"
        const val STARTLIGHT_DEBUG = "net.jolabs40.startlight.debug"
        const val PROJECTIVY = "com.spocky.projengmenu"
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
    }
}
