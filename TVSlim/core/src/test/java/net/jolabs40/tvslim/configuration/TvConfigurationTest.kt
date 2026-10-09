package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.KnownLauncher
import net.jolabs40.tvslim.catalog.RecommendedLauncher
import net.jolabs40.tvslim.catalog.ProtectedPackage
import net.jolabs40.tvslim.device.FactoryHome
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.InstalledLauncher
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
class TvConfigurationTest {

    private fun entry(packageName: String, order: Int = 100, home: Boolean = false) = PackageEntry(
        packageName = packageName,
        name = "Nom de $packageName",
        description = "",
        category = "test",
        order = order,
        requiresThirdPartyLauncher = home,
    )

    private val catalog = Catalog(
        entries = listOf(
            entry("com.tcl.pub"),
            entry("com.tcl.demo"),
            entry("com.tcl.absent"),
            entry(SETUPWRAITH, order = 1, home = true),
            entry(LAUNCHERX, order = 2, home = true),
        ),
        protectedPackages = listOf(ProtectedPackage("com.android.location.fused", "Boucle de redémarrage.")),
        launchers = listOf(
            RecommendedLauncher(
                packageName = STARTLIGHT,
                name = "Startlight Launcher",
                description = "",
                id = "startlight",
                variants = listOf(STARTLIGHT_DEBUG),
            ),
        ),
        knownLaunchers = listOf(KnownLauncher("projectivy", "Projectivy Launcher", listOf(PROJECTIVY))),
    )

    private fun launcher(packageName: String) = InstalledLauncher(packageName, packageName, "$packageName/.Accueil")

    /** The reference TCL: debug Startlight as home app, Google TV disabled. */
    private val tcl = DeviceInfo(
        brand = "TCL",
        model = "Smart TV Pro",
        androidVersion = "14",
        currentHome = STARTLIGHT_DEBUG,
        homeComponent = "$STARTLIGHT_DEBUG/.Accueil",
        thirdPartyLaunchers = listOf(launcher(PROJECTIVY), launcher(STARTLIGHT_DEBUG)),
        factoryHomes = listOf(FactoryHome(LAUNCHERX, "$LAUNCHERX/.home.HomeActivity", active = false)),
    )

    private fun backup(
        disabled: List<String> = emptyList(),
        active: List<String> = emptyList(),
        home: SavedHome? = null,
    ) = TvConfiguration(
        application = TvConfiguration.APPLICATION,
        format = TvConfiguration.FORMAT,
        savedAt = 0,
        home = home,
        disabled = disabled,
        active = active,
    )

    // --- File ----------------------------------------------------------------------------------

    @Test
    fun `saving keeps the state of catalog packages and the current home app`() {
        val configuration = catalog.configurationOf(
            info = tcl,
            states = mapOf(
                "com.tcl.pub" to PackageState.DISABLED,
                "com.tcl.demo" to PackageState.ACTIVE,
                "com.tcl.absent" to PackageState.ABSENT,
            ),
            now = 1_789_300_000_000,
        )

        assertEquals(listOf("com.tcl.pub"), configuration.disabled)
        assertEquals(listOf("com.tcl.demo"), configuration.active)
        assertEquals(STARTLIGHT_DEBUG, configuration.home?.packageName)
        assertEquals("$STARTLIGHT_DEBUG/.Accueil", configuration.home?.component)
        assertEquals("Startlight Launcher", configuration.home?.name)
        assertEquals("TCL Smart TV Pro", configuration.device.name)
        assertEquals(1_789_300_000_000, configuration.savedAt)
    }

    @Test
    fun `the Android chooser is not saved as a home app`() {
        val withoutChoice = tcl.copy(currentHome = "android", homeComponent = "android/.ResolverActivity")

        assertNull(catalog.configurationOf(withoutChoice, emptyMap()).home)
    }

    @Test
    fun `a written configuration reads back unchanged`() {
        val configuration = catalog.configurationOf(tcl, mapOf("com.tcl.pub" to PackageState.DISABLED), 42)

        assertEquals(configuration, ConfigurationFile.read(ConfigurationFile.write(configuration)))
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
        ).forEach { text ->
            assertNull("Accepté à tort : $text", ConfigurationFile.read(text))
        }
    }

    @Test
    fun `a field added by a future version does not make the file unreadable`() {
        val text = """{"application": "TV Slim", "format": 1, "sauvegardeLe": 0, "reglages": ["x"]}"""

        assertNotNull(ConfigurationFile.read(text))
    }

    @Test
    fun `the suggested file name gives the device and the date`() {
        val day = LocalDate.of(2026, 9, 13)
        val philips = DeviceInfo(brand = "TPV", retailBrand = "Philips", model = "55PUS8807/12")

        assertEquals("TVSlim-Philips-55PUS8807-12-2026-09-13.json", ConfigurationFile.suggestedName(philips, day))
        assertEquals("TVSlim-televiseur-2026-09-13.json", ConfigurationFile.suggestedName(DeviceInfo.EMPTY, day))
    }

    // --- Plan ----------------------------------------------------------------------------------

    @Test
    fun `the plan keeps only the differences, in both directions`() {
        val plan = backup(
            disabled = listOf("com.tcl.pub", "com.tcl.demo"),
            active = listOf("com.tcl.absent", "com.retire.du.catalogue", SETUPWRAITH),
        ).buildPlan(
            catalog = catalog,
            states = mapOf(
                "com.tcl.pub" to PackageState.ACTIVE,
                "com.tcl.demo" to PackageState.DISABLED,
                "com.tcl.absent" to PackageState.ABSENT,
                SETUPWRAITH to PackageState.DISABLED,
            ),
            info = tcl,
        )

        assertEquals(listOf("com.tcl.pub"), plan.toDisable.map { it.packageName })
        assertEquals(listOf(SETUPWRAITH), plan.toEnable.map { it.packageName })
        assertEquals(listOf("com.tcl.absent", "com.retire.du.catalogue"), plan.ignores)
        assertEquals(2, plan.actionCount)
    }

    @Test
    fun `a protected package is never planned for disabling`() {
        val withProtected = catalog.copy(entries = catalog.entries + entry("com.android.location.fused"))

        val plan = backup(disabled = listOf("com.android.location.fused"))
            .buildPlan(withProtected, mapOf("com.android.location.fused" to PackageState.ACTIVE), tcl)

        assertTrue(plan.toDisable.isEmpty())
        assertTrue(plan.nothingToDo)
    }

    @Test
    fun `an installed home app is set by its component`() {
        val plan = backup(home = SavedHome(PROJECTIVY, name = "Projectivy Launcher"))
            .buildPlan(catalog, emptyMap(), tcl)

        assertEquals(HomeChange(PROJECTIVY, "Projectivy Launcher", "$PROJECTIVY/.Accueil"), plan.home)
        assertTrue(plan.home!!.possible)
    }

    @Test
    fun `another build of the same launcher will do`() {
        // Saved on a TV with the release build; this one only has the debug build.
        val elsewhere = tcl.copy(currentHome = PROJECTIVY, homeComponent = "$PROJECTIVY/.Accueil")

        val plan = backup(home = SavedHome(STARTLIGHT, name = "Startlight Launcher"))
            .buildPlan(catalog, emptyMap(), elsewhere)

        assertEquals(STARTLIGHT_DEBUG, plan.home?.packageName)
        assertTrue(plan.home!!.possible)
    }

    @Test
    fun `a home app already set, even as another build, needs no action`() {
        val plan = backup(home = SavedHome(STARTLIGHT)).buildPlan(catalog, emptyMap(), tcl)

        assertNull(plan.home)
        assertTrue(plan.nothingToDo)
    }

    @Test
    fun `a missing launcher keeps its name but cannot be set`() {
        val withoutProjectivy = tcl.copy(thirdPartyLaunchers = listOf(launcher(STARTLIGHT_DEBUG)))

        val plan = backup(home = SavedHome(PROJECTIVY, name = "Projectivy Launcher"))
            .buildPlan(catalog, emptyMap(), withoutProjectivy)

        assertEquals("Projectivy Launcher", plan.home?.name)
        assertFalse(plan.home!!.possible)
        assertEquals(0, plan.actionCount)
    }

    @Test
    fun `a disabled factory home app is found and restored`() {
        val plan = backup(active = listOf(LAUNCHERX), home = SavedHome(LAUNCHERX))
            .buildPlan(catalog, mapOf(LAUNCHERX to PackageState.DISABLED), tcl)

        assertEquals(listOf(LAUNCHERX), plan.toEnable.map { it.packageName })
        assertEquals("$LAUNCHERX/.home.HomeActivity", plan.home?.component)
        assertEquals(2, plan.actionCount)
    }

    private companion object {
        const val STARTLIGHT = "net.jolabs40.startlight"
        const val STARTLIGHT_DEBUG = "net.jolabs40.startlight.debug"
        const val PROJECTIVY = "com.spocky.projengmenu"
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
    }
}
