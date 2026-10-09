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
 * Saving and restoring a configuration. The file reads back unchanged and anything else is rejected; the plan only
 * promises what the TV can actually apply.
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

    /** The reference TCL: debug Startlight as home app, Google TV disabled. */
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

    // --- File ----------------------------------------------------------------------------------

    @Test
    fun `saving keeps the state of catalog packages and the current home app`() {
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
    fun `the Android chooser is not saved as a home app`() {
        val sansChoix = tcl.copy(accueilActuel = "android", composantAccueil = "android/.ResolverActivity")

        assertNull(catalogue.configurationDe(sansChoix, emptyMap()).accueil)
    }

    @Test
    fun `a written configuration reads back unchanged`() {
        val configuration = catalogue.configurationDe(tcl, mapOf("com.tcl.pub" to EtatPaquet.DESACTIVE), 42)

        assertEquals(configuration, FichierConfiguration.lire(FichierConfiguration.ecrire(configuration)))
    }

    @Test
    fun `a file that is not a TV Slim configuration is rejected`() {
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
    fun `a field added by a future version does not make the file unreadable`() {
        val texte = """{"application": "TV Slim", "format": 1, "sauvegardeLe": 0, "reglages": ["x"]}"""

        assertNotNull(FichierConfiguration.lire(texte))
    }

    @Test
    fun `the suggested file name gives the device and the date`() {
        val jour = LocalDate.of(2026, 9, 13)
        val philips = InfosAppareil(marque = "TPV", marqueCommerciale = "Philips", modele = "55PUS8807/12")

        assertEquals("TVSlim-Philips-55PUS8807-12-2026-09-13.json", FichierConfiguration.nomPropose(philips, jour))
        assertEquals("TVSlim-televiseur-2026-09-13.json", FichierConfiguration.nomPropose(InfosAppareil.VIDE, jour))
    }

    // --- Plan ----------------------------------------------------------------------------------

    @Test
    fun `the plan keeps only the differences, in both directions`() {
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
    fun `a protected package is never planned for disabling`() {
        val avecProtege = catalogue.copy(entrees = catalogue.entrees + entree("com.android.location.fused"))

        val plan = sauvegarde(desactives = listOf("com.android.location.fused"))
            .planifier(avecProtege, mapOf("com.android.location.fused" to EtatPaquet.ACTIF), tcl)

        assertTrue(plan.aDesactiver.isEmpty())
        assertTrue(plan.rienAFaire)
    }

    @Test
    fun `an installed home app is set by its component`() {
        val plan = sauvegarde(accueil = AccueilSauvegarde(PROJECTIVY, nom = "Projectivy Launcher"))
            .planifier(catalogue, emptyMap(), tcl)

        assertEquals(ChangementAccueil(PROJECTIVY, "Projectivy Launcher", "$PROJECTIVY/.Accueil"), plan.accueil)
        assertTrue(plan.accueil!!.possible)
    }

    @Test
    fun `another build of the same launcher will do`() {
        // Saved on a TV with the release build; this one only has the debug build.
        val ailleurs = tcl.copy(accueilActuel = PROJECTIVY, composantAccueil = "$PROJECTIVY/.Accueil")

        val plan = sauvegarde(accueil = AccueilSauvegarde(STARTLIGHT, nom = "Startlight Launcher"))
            .planifier(catalogue, emptyMap(), ailleurs)

        assertEquals(STARTLIGHT_DEBUG, plan.accueil?.paquet)
        assertTrue(plan.accueil!!.possible)
    }

    @Test
    fun `a home app already set, even as another build, needs no action`() {
        val plan = sauvegarde(accueil = AccueilSauvegarde(STARTLIGHT)).planifier(catalogue, emptyMap(), tcl)

        assertNull(plan.accueil)
        assertTrue(plan.rienAFaire)
    }

    @Test
    fun `a missing launcher keeps its name but cannot be set`() {
        val sansProjectivy = tcl.copy(launchersTiers = listOf(launcher(STARTLIGHT_DEBUG)))

        val plan = sauvegarde(accueil = AccueilSauvegarde(PROJECTIVY, nom = "Projectivy Launcher"))
            .planifier(catalogue, emptyMap(), sansProjectivy)

        assertEquals("Projectivy Launcher", plan.accueil?.nom)
        assertFalse(plan.accueil!!.possible)
        assertEquals(0, plan.nombreActions)
    }

    @Test
    fun `a disabled factory home app is found and restored`() {
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
