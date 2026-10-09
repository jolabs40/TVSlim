package net.jolabs40.tvslim.configuration

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.InstalledLauncher
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Reinjection goes through the engine in an order that never leaves the TV without a home screen. */
class ReinjectorTest {

    /** Answers like the TV and records every command. */
    private class SpyExecutor : CommandExecutor {
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): ShellResult {
            commands += command
            val packageName = command.substringAfterLast(' ')
            return when {
                command.startsWith("pm enable") -> ShellResult(0, "Package $packageName new state: enabled")
                command.startsWith("pm disable-user") -> ShellResult(0, "Package $packageName new state: disabled-user")
                else -> ShellResult(0, "Success")
            }
        }
    }

    private fun entry(packageName: String, order: Int = 100, home: Boolean = false) = PackageEntry(
        packageName = packageName,
        name = packageName,
        description = "",
        category = "test",
        order = order,
        requiresThirdPartyLauncher = home,
    )

    private val demo = entry("com.tcl.demo")
    private val setupwraith = entry("com.google.android.tungsten.setupwraith", order = 1, home = true)
    private val launcherx = entry("com.google.android.apps.tv.launcherx", order = 2, home = true)
    private val catalog = Catalog(entries = listOf(demo, setupwraith, launcherx))

    private val states = mapOf(
        demo.packageName to PackageState.DISABLED,
        setupwraith.packageName to PackageState.ACTIVE,
        launcherx.packageName to PackageState.ACTIVE,
    )

    private val startlight = "net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity"

    private val tcl = DeviceInfo(
        currentHome = "com.google.android.apps.tv.launcherx",
        homeComponent = "com.google.android.apps.tv.launcherx/.home.HomeActivity",
        thirdPartyLaunchers = listOf(InstalledLauncher("net.jolabs40.startlight.debug", "Startlight", startlight)),
    )

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun plan(home: HomeChange?) = ReinjectionPlan(
        configuration = TvConfiguration(TvConfiguration.APPLICATION, TvConfiguration.FORMAT, savedAt = 0),
        toEnable = listOf(demo),
        // Reversed on purpose: the catalogue order must win.
        toDisable = listOf(launcherx, setupwraith),
        ignores = emptyList(),
        home = home,
    )

    @Test
    fun `enables first, disables in catalogue order, and sets the home screen last`() = runTest {
        val spy = SpyExecutor()
        val steps = mutableListOf<Pair<Int, Int>>()

        val results = Reinjector(DebloatEngine(spy, journal())).reinject(
            plan = plan(HomeChange("net.jolabs40.startlight.debug", "Startlight", startlight)),
            catalog = catalog,
            states = states,
            info = tcl,
            onProgress = { done, total -> steps += done to total },
        )

        assertEquals(
            listOf(
                "pm enable com.tcl.demo",
                "pm disable-user --user 0 com.google.android.tungsten.setupwraith",
                "pm disable-user --user 0 com.google.android.apps.tv.launcherx",
                "cmd package set-home-activity $startlight",
            ),
            spy.commands,
        )
        assertTrue("Everything must succeed: $results", results.all { it.succeeded })
        assertEquals(4 to 4, steps.last())
    }

    @Test
    fun `without a third-party launcher, the factory home stays in place despite the backup`() = runTest {
        val spy = SpyExecutor()

        val results = Reinjector(DebloatEngine(spy, journal())).reinject(
            plan = plan(HomeChange("net.jolabs40.startlight.debug", "Startlight", component = "")),
            catalog = catalog,
            states = states,
            info = tcl.copy(thirdPartyLaunchers = emptyList()),
        )

        assertEquals(listOf("pm enable com.tcl.demo"), spy.commands)
        assertEquals(2, results.count { !it.succeeded })
    }
}
