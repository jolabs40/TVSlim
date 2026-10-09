package net.jolabs40.tvslim.engine

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.ProtectedPackage
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine's guardrails. This code can leave a TV unusable: disabling the home screen with no
 * replacement, disabling a service boot depends on, or disabling in the order that hands control
 * to a recovery screen. Each rule was learned by hand on a real TCL.
 */
class DebloatEngineTest {

    /** Fake executor that records commands and returns canned replies. */
    private class SpyExecutor(
        private val response: (String) -> ShellResult = { ShellResult(0, "new state: disabled-user") },
    ) : CommandExecutor {
        val commands = mutableListOf<String>()

        override suspend fun execute(command: String): ShellResult {
            commands += command
            return response(command)
        }
    }

    private fun journal() = JournalRepository(
        File.createTempFile("journal", ".json").also { it.delete() },
    )

    private fun entry(
        packageName: String,
        order: Int = 100,
        requiresThirdPartyLauncher: Boolean = false,
    ) = PackageEntry(
        packageName = packageName,
        name = packageName,
        description = "",
        category = "test",
        order = order,
        requiresThirdPartyLauncher = requiresThirdPartyLauncher,
    )

    private val catalog = Catalog(
        protectedPackages = listOf(
            ProtectedPackage("com.android.location.fused", "Boot loop."),
        ),
    )

    @Test
    fun `a blacklisted package is refused and no command is sent`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())

        val results = engine.disable(
            entries = listOf(entry("com.android.location.fused")),
            catalog = catalog,
            states = mapOf("com.android.location.fused" to PackageState.ACTIVE),
            launchersAvailable = true,
        )

        assertFalse(results.single().succeeded)
        assertTrue(results.single().reason is EngineReason.Protected)
        assertTrue("No command may be sent: ${spy.commands}", spy.commands.isEmpty())
    }

    @Test
    fun `the home screen is refused without a third-party launcher`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())

        val results = engine.disable(
            entries = listOf(entry("com.google.android.apps.tv.launcherx", requiresThirdPartyLauncher = true)),
            catalog = catalog,
            states = mapOf("com.google.android.apps.tv.launcherx" to PackageState.ACTIVE),
            launchersAvailable = false,
        )

        assertFalse(results.single().succeeded)
        assertEquals(EngineReason.NoThirdPartyLauncher, results.single().reason)
        assertTrue(spy.commands.isEmpty())
    }

    @Test
    fun `setupwraith is disabled before launcherx, whatever the selection order`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())
        val launcherx = entry("com.google.android.apps.tv.launcherx", order = 2, requiresThirdPartyLauncher = true)
        val setupwraith = entry("com.google.android.tungsten.setupwraith", order = 1, requiresThirdPartyLauncher = true)

        engine.disable(
            // Deliberately in the wrong order: the engine must fix it.
            entries = listOf(launcherx, setupwraith),
            catalog = catalog,
            states = mapOf(
                launcherx.packageName to PackageState.ACTIVE,
                setupwraith.packageName to PackageState.ACTIVE,
            ),
            launchersAvailable = true,
        )

        assertEquals(2, spy.commands.size)
        assertTrue(
            "setupwraith must go first: ${spy.commands}",
            spy.commands[0].contains("setupwraith"),
        )
        assertTrue(spy.commands[1].contains("launcherx"))
    }

    @Test
    fun `a missing or already disabled package triggers no command`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())

        val results = engine.disable(
            entries = listOf(entry("absent.from.tv"), entry("already.disabled")),
            catalog = catalog,
            states = mapOf(
                "absent.from.tv" to PackageState.ABSENT,
                "already.disabled" to PackageState.DISABLED,
            ),
            launchersAvailable = true,
        )

        assertTrue(spy.commands.isEmpty())
        assertFalse(results.first { it.packageName == "absent.from.tv" }.succeeded)
        // Already disabled counts as success: the target state is reached.
        assertTrue(results.first { it.packageName == "already.disabled" }.succeeded)
    }

    @Test
    fun `disabling never uses uninstall and logs its undo command`() = runTest {
        val spy = SpyExecutor()
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        engine.disable(
            entries = listOf(entry("com.tcl.gallery")),
            catalog = catalog,
            states = mapOf("com.tcl.gallery" to PackageState.ACTIVE),
            launchersAvailable = true,
        )

        assertEquals("pm disable-user --user 0 com.tcl.gallery", spy.commands.single())
        assertFalse("Never uninstall", spy.commands.any { it.contains("uninstall") })

        val action = logbook.actions.value.single()
        assertEquals(ActionType.DISABLING, action.type)
        assertEquals("pm enable com.tcl.gallery", action.undoCommand)
        assertTrue(action.succeeded)
        assertEquals(listOf("com.tcl.gallery"), logbook.activelyDisabledPackages())
    }

    @Test
    fun `unexpected output is a failure, even with exit code zero`() = runTest {
        // The package manager sometimes exits 0 without doing anything; the output is what counts.
        val spy = SpyExecutor { ShellResult(0, "Success") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val results = engine.disable(
            entries = listOf(entry("com.tcl.gallery")),
            catalog = catalog,
            states = mapOf("com.tcl.gallery" to PackageState.ACTIVE),
            launchersAvailable = true,
        )

        assertFalse(results.single().succeeded)
        assertFalse(logbook.actions.value.single().succeeded)
        // A failure must not end up in the restore list.
        assertTrue(logbook.activelyDisabledPackages().isEmpty())
    }

    @Test
    fun `re-enabling undoes the disable in the log`() = runTest {
        val spy = SpyExecutor { command ->
            if (command.startsWith("pm enable")) {
                ShellResult(0, "new state: enabled")
            } else {
                ShellResult(0, "new state: disabled-user")
            }
        }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        engine.disable(
            entries = listOf(entry("com.tcl.gallery")),
            catalog = catalog,
            states = mapOf("com.tcl.gallery" to PackageState.ACTIVE),
            launchersAvailable = true,
        )
        assertEquals(listOf("com.tcl.gallery"), logbook.activelyDisabledPackages())

        engine.enable(listOf("com.tcl.gallery"))

        assertTrue(
            "The re-enabled package leaves the restore list",
            logbook.activelyDisabledPackages().isEmpty(),
        )
        assertEquals(2, logbook.actions.value.size)
    }

    @Test
    fun `a setting logs the command that restores the previous value`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        engine.writeSetting(
            key = "low_power_standby_enabled",
            scope = "global",
            name = "Standby",
            rawValue = "0",
            previousValue = "1",
        )

        assertEquals("settings put global low_power_standby_enabled 0", spy.commands.single())
        assertEquals(
            "settings put global low_power_standby_enabled 1",
            logbook.actions.value.single().undoCommand,
        )
    }

    // --- Privileged permissions -------------------------------------------------------------
    //
    // The only engine commands built from typed input rather than the catalogue, so the input
    // is treated as hostile.

    @Test
    fun `a permission missing from the manifest is not granted`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val result = engine.grantPermission(
            packageName = "net.jolabs40.hippietv.launcher.debug",
            permission = "android.permission.DUMP",
            declaredPermissions = setOf("android.permission.INTERNET"),
        )

        assertFalse(result.succeeded)
        assertTrue("Nothing is sent to the TV", spy.commands.isEmpty())
        assertTrue("A refusal is not journaled", logbook.actions.value.isEmpty())
    }

    @Test
    fun `input that would start a second command is refused`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val engine = DebloatEngine(spy, journal())

        val result = engine.grantPermission(
            packageName = "com.tcl.gallery; reboot",
            permission = "android.permission.DUMP",
            declaredPermissions = setOf("android.permission.DUMP"),
        )

        assertFalse(result.succeeded)
        assertTrue("The semicolon must never reach the shell", spy.commands.isEmpty())
    }

    @Test
    fun `granting logs the revoke that undoes it`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val result = engine.grantPermission(
            packageName = "net.jolabs40.hippietv.launcher.debug",
            permission = "android.permission.DUMP",
            declaredPermissions = setOf("android.permission.DUMP"),
        )

        assertTrue(result.message, result.succeeded)
        assertEquals(
            "pm grant net.jolabs40.hippietv.launcher.debug android.permission.DUMP",
            spy.commands.single(),
        )
        assertEquals(
            "pm revoke net.jolabs40.hippietv.launcher.debug android.permission.DUMP",
            logbook.actions.value.single().undoCommand,
        )
        assertEquals(
            mapOf(
                "net.jolabs40.hippietv.launcher.debug android.permission.DUMP" to
                    "pm revoke net.jolabs40.hippietv.launcher.debug android.permission.DUMP",
            ),
            logbook.permissionUndos(),
        )
    }

    @Test
    fun `a chatty pm grant is still a failure despite exit code zero`() = runTest {
        // `pm grant` prints nothing on success. A Java exception with exit code 0 is the same
        // trap as with `pm disable-user`.
        val spy = SpyExecutor {
            ShellResult(0, "java.lang.SecurityException: Permission is not a changeable")
        }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val result = engine.grantPermission(
            packageName = "com.tcl.gallery",
            permission = "android.permission.DUMP",
            declaredPermissions = setOf("android.permission.DUMP"),
        )

        assertFalse("Unexpected output is still a failure", result.succeeded)
        assertFalse(logbook.actions.value.single().succeeded)
    }

    @Test
    fun `revoking a permission does not check the manifest`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val result = engine.revokePermission("com.tcl.gallery", "android.permission.DUMP")

        assertTrue(result.succeeded)
        assertEquals("pm revoke com.tcl.gallery android.permission.DUMP", spy.commands.single())
        assertEquals(
            "pm grant com.tcl.gallery android.permission.DUMP",
            logbook.actions.value.single().undoCommand,
        )
    }
    @Test
    fun `an app-op logs the return to its previous mode`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val result = engine.setAppOp(
            packageName = "net.jolabs40.hippietv.launcher.debug",
            appOp = "GET_USAGE_STATS",
            mode = "allow",
            previousMode = "ignore",
        )

        assertTrue(result.message, result.succeeded)
        assertEquals(
            "cmd appops set net.jolabs40.hippietv.launcher.debug GET_USAGE_STATS allow",
            spy.commands.single(),
        )
        assertEquals(
            "cmd appops set net.jolabs40.hippietv.launcher.debug GET_USAGE_STATS ignore",
            logbook.actions.value.single().undoCommand,
        )
    }

    @Test
    fun `an unknown app-op mode is refused`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val engine = DebloatEngine(spy, journal())

        val result = engine.setAppOp(
            packageName = "com.tcl.gallery",
            appOp = "GET_USAGE_STATS",
            mode = "allowed",
            previousMode = "default",
        )

        assertFalse(result.succeeded)
        assertTrue(spy.commands.isEmpty())
    }

    @Test
    fun `an unknown previous mode is restored as default`() = runTest {
        // The previous mode could not be read; the undo command must still be runnable.
        val spy = SpyExecutor { ShellResult(0, "") }
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        engine.setAppOp("com.tcl.gallery", "GET_USAGE_STATS", "allow", previousMode = "")

        assertEquals(
            "cmd appops set com.tcl.gallery GET_USAGE_STATS default",
            logbook.actions.value.single().undoCommand,
        )
    }

    // --- Targets read from device output -------------------------------------------------
    //
    // These values come from `dumpsys` and `cmd package` output, which Android constrains,
    // but they are validated like every other engine target anyway.

    @Test
    fun `a malformed home component is refused and no command is sent`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())

        val result = engine.setHome(
            component = "com.example/.Main; rm -rf /sdcard",
            oldHome = "com.tcl.launcher/.Home",
        )

        assertFalse(result.succeeded)
        assertTrue(spy.commands.isEmpty())
    }

    @Test
    fun `a malformed previous home is refused too, since it is replayed from the log`() = runTest {
        val spy = SpyExecutor()
        val logbook = journal()
        val engine = DebloatEngine(spy, logbook)

        val result = engine.setHome(
            component = "com.spocky.projengmenu/.MainActivity",
            oldHome = "it's nonsense",
        )

        assertFalse(result.succeeded)
        assertTrue(spy.commands.isEmpty())
        assertTrue(logbook.actions.value.isEmpty())
    }

    @Test
    fun `a well-formed component goes through`() = runTest {
        val spy = SpyExecutor { ShellResult(0, "") }
        val engine = DebloatEngine(spy, journal())

        engine.setHome("com.spocky.projengmenu/.MainActivity", "com.tcl.launcher/.Home")

        assertEquals(
            "cmd package set-home-activity com.spocky.projengmenu/.MainActivity",
            spy.commands.single(),
        )
    }

    @Test
    fun `force-stopping a suspicious process name is refused`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())

        // This name comes from a regex over `dumpsys meminfo` output.
        val result = engine.forceStop("com.tcl.gallery; reboot")

        assertFalse(result.succeeded)
        assertTrue(spy.commands.isEmpty())
    }

    @Test
    fun `opening a store listing also validates the package name`() = runTest {
        val spy = SpyExecutor()
        val engine = DebloatEngine(spy, journal())

        val result = engine.openStoreListing("com.spocky.projengmenu&id=other")

        assertFalse(result.succeeded)
        assertTrue(spy.commands.isEmpty())
    }
}
