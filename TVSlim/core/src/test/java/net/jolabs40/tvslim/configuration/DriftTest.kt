package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.RecommendedLauncher
import net.jolabs40.tvslim.catalog.ProtectedPackage
import net.jolabs40.tvslim.device.FactoryHome
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.InstalledLauncher
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift: what the journal disabled and the TV turned back on. Nothing is offered on a TV that matches, and a
 * choice the user made since is not taken for drift.
 */
class DriftTest {

    private fun entry(packageName: String, order: Int = 100, home: Boolean = false) = PackageEntry(
        packageName = packageName,
        name = "Name of $packageName",
        description = "",
        category = "test",
        order = order,
        requiresThirdPartyLauncher = home,
    )

    private val catalog = Catalog(
        entries = listOf(
            entry("com.tcl.pub"),
            entry("com.tcl.demo"),
            entry(SETUPWRAITH, order = 1, home = true),
            entry(LAUNCHERX, order = 2, home = true),
        ),
        protectedPackages = listOf(ProtectedPackage(PROTECTED, "Boot loop.")),
        launchers = listOf(
            RecommendedLauncher(packageName = STARTLIGHT, name = "Startlight Launcher", description = "", id = "startlight"),
        ),
    )

    private fun action(type: ActionType, target: String, succeeded: Boolean = true) = JournalAction(
        timestamp = 0,
        type = type,
        target = target,
        label = "",
        undoCommand = "",
        succeeded = succeeded,
    )

    /** TCL journal: ads, Google TV and its setup app disabled, Startlight set as home app. */
    private val journal = listOf(
        action(ActionType.DISABLING, "com.tcl.pub"),
        action(ActionType.DISABLING, SETUPWRAITH),
        action(ActionType.DISABLING, LAUNCHERX),
        action(ActionType.HOME, "$STARTLIGHT/.HomeActivity"),
    )

    /** The TV as TV Slim left it. */
    private val compliant = mapOf(
        "com.tcl.pub" to PackageState.DISABLED,
        "com.tcl.demo" to PackageState.ACTIVE,
        SETUPWRAITH to PackageState.DISABLED,
        LAUNCHERX to PackageState.DISABLED,
    )

    private val tcl = DeviceInfo(
        currentHome = STARTLIGHT,
        homeComponent = "$STARTLIGHT/.HomeActivity",
        thirdPartyLaunchers = listOf(InstalledLauncher(STARTLIGHT, "Startlight", "$STARTLIGHT/.HomeActivity")),
        factoryHomes = listOf(FactoryHome(LAUNCHERX, "$LAUNCHERX/.home.HomeActivity", active = false)),
    )

    /** After a system update: everything disabled is back on, and Google TV is the home app again. */
    private val afterUpdate = compliant.mapValues { PackageState.ACTIVE }
    private val tclAfterUpdate = tcl.copy(
        currentHome = LAUNCHERX,
        homeComponent = "$LAUNCHERX/.home.HomeActivity",
        factoryHomes = listOf(FactoryHome(LAUNCHERX, "$LAUNCHERX/.home.HomeActivity", active = true)),
    )

    @Test
    fun `a TV left as TV Slim set it has no drift`() {
        assertNull(catalog.driftPlan(journal, compliant, tcl))
    }

    @Test
    fun `without a journal there is nothing to compare`() {
        assertNull(catalog.driftPlan(emptyList(), afterUpdate, tclAfterUpdate))
    }

    @Test
    fun `after an update, re-enabled packages and the home app are planned again`() {
        val plan = checkNotNull(catalog.driftPlan(journal, afterUpdate, tclAfterUpdate))

        assertEquals(listOf("com.tcl.pub", SETUPWRAITH, LAUNCHERX).sorted(), plan.toDisable.map { it.packageName }.sorted())
        assertTrue(plan.toEnable.isEmpty())
        assertEquals("$STARTLIGHT/.HomeActivity", plan.home?.component)
        assertEquals(4, plan.actionCount)
    }

    @Test
    fun `a package restored from TV Slim is not drift`() {
        val restored = journal + action(ActionType.ENABLING, "com.tcl.pub")
        val states = compliant + ("com.tcl.pub" to PackageState.ACTIVE)

        assertNull(catalog.driftPlan(restored, states, tcl))
    }

    @Test
    fun `a failed disable is not a wanted state`() {
        val failed = listOf(action(ActionType.DISABLING, "com.tcl.demo", succeeded = false))

        assertNull(catalog.driftPlan(failed, compliant, tcl))
    }

    @Test
    fun `another launcher picked since is a choice, not drift`() {
        val projectivy = tcl.copy(currentHome = PROJECTIVY, homeComponent = "$PROJECTIVY/.Main")

        assertNull(catalog.driftPlan(journal, compliant, projectivy))
    }

    @Test
    fun `the Android chooser counts as a lost home app`() {
        val selector = tcl.copy(currentHome = "android", homeComponent = "android/.ResolverActivity")
        val plan = checkNotNull(catalog.driftPlan(journal, compliant, selector))

        assertTrue(plan.toDisable.isEmpty())
        assertEquals("$STARTLIGHT/.HomeActivity", plan.home?.component)
    }

    @Test
    fun `a launcher uninstalled since is not offered again`() {
        val withoutStartlight = tclAfterUpdate.copy(thirdPartyLaunchers = emptyList())
        val plan = checkNotNull(catalog.driftPlan(journal, afterUpdate, withoutStartlight))

        assertNull(plan.home)
    }

    @Test
    fun `a missing or protected package is never offered`() {
        val withProtected = journal + action(ActionType.DISABLING, PROTECTED) +
            action(ActionType.DISABLING, "com.tcl.retire")
        val states = compliant + (PROTECTED to PackageState.ACTIVE)

        assertNull(catalog.driftPlan(withProtected, states, tcl))
    }

    private companion object {
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
        const val STARTLIGHT = "net.jolabs40.startlight"
        const val PROJECTIVY = "com.spocky.projengmenu"
        const val PROTECTED = "com.android.location.fused"
    }
}
