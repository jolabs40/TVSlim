package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guardian compares two boots and only reports after a system update, and only what it undid. */
class BootSnapshotTest {

    private val factories = setOf(SETUPWRAITH, LAUNCHERX)

    /** The TCL the day before: ads and Google TV disabled, Startlight as home. */
    private val dayBefore = BootSnapshot(
        fingerprint = "TCL/G08_4K_GB/14:20251205",
        disabled = setOf("com.tcl.pub", SETUPWRAITH, LAUNCHERX),
        home = STARTLIGHT,
    )

    /** After the update: everything is back and Google TV is home again. */
    private val afterUpdate = BootSnapshot(
        fingerprint = "TCL/G08_4K_GB/14:20260312",
        disabled = emptySet(),
        home = LAUNCHERX,
    )
    private val allActive = setOf("com.tcl.pub", "com.tcl.demo", SETUPWRAITH, LAUNCHERX)

    @Test
    fun `the first boot only sets the baseline`() {
        assertNull(afterUpdate.driftSince(null, allActive, factories))
    }

    @Test
    fun `without a system update nothing has drifted`() {
        // Same firmware: a re-enabled package was turned on from the phone, on purpose.
        val sameFirmware = afterUpdate.copy(fingerprint = dayBefore.fingerprint)

        assertNull(sameFirmware.driftSince(dayBefore, allActive, factories))
    }

    @Test
    fun `an update that re-enables everything and gives home back to Google TV`() {
        val drift = afterUpdate.driftSince(dayBefore, allActive, factories)

        assertEquals(listOf("com.tcl.pub", LAUNCHERX, SETUPWRAITH).sorted(), drift?.reenabled)
        assertEquals(STARTLIGHT, drift?.lostHome)
    }

    @Test
    fun `an update that undoes nothing reports nothing`() {
        val stable = dayBefore.copy(fingerprint = afterUpdate.fingerprint)
        val active = allActive - dayBefore.disabled

        assertNull(stable.driftSince(dayBefore, active, factories))
    }

    @Test
    fun `a package removed by the update is not reported as re-enabled`() {
        val active = allActive - "com.tcl.pub"
        val drift = afterUpdate.driftSince(dayBefore, active, factories)

        assertEquals(listOf(LAUNCHERX, SETUPWRAITH).sorted(), drift?.reenabled)
    }

    @Test
    fun `a factory home before the update is not lost`() {
        val onGoogleTv = dayBefore.copy(home = LAUNCHERX)
        val drift = afterUpdate.driftSince(onGoogleTv, allActive, factories)

        assertNull(drift?.lostHome)
    }

    @Test
    fun `the Android chooser counts as a home that fell back`() {
        val selector = afterUpdate.copy(home = "android")

        assertEquals(STARTLIGHT, selector.driftSince(dayBefore, allActive, factories)?.lostHome)
    }

    @Test
    fun `what has been fixed since does not remain`() {
        val drift = BootDrift(reenabled = listOf("com.tcl.pub", LAUNCHERX), lostHome = STARTLIGHT)
        // The phone disabled Google TV again and restored Startlight; the ads package is still on.
        val remaining = drift.remaining(active = setOf("com.tcl.pub"), home = STARTLIGHT, factoryHomes = factories)

        assertEquals(listOf("com.tcl.pub"), remaining.reenabled)
        assertNull(remaining.lostHome)
        assertTrue(drift.remaining(emptySet(), STARTLIGHT, factories).empty)
    }

    private companion object {
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
        const val STARTLIGHT = "net.jolabs40.startlight"
    }
}
