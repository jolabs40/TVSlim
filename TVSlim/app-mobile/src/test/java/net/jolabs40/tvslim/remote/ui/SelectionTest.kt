package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.Profile
import net.jolabs40.tvslim.device.PackageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection decides what gets disabled on a TV, so it must never check an inactive package:
 * that would offer to disable something already disabled, or missing from the device.
 */
class SelectionTest {

    private fun entry(packageName: String, category: String = "bloatware_tcl") = PackageEntry(
        packageName = packageName,
        name = packageName,
        description = "",
        category = category,
    )

    private fun state(vararg lines: Pair<String, PackageState>) = RemoteState(
        lines = lines.map { (packageName, state) -> PackageRow(entry(packageName), state) },
    )

    private fun profile(vararg categories: String) =
        Profile(id = "doux", name = "Doux", description = "", categories = categories.toList())

    @Test
    fun `toggling checks then unchecks an active package`() {
        val start = state("com.tcl.pub" to PackageState.ACTIVE)

        val checked = start.withToggled("com.tcl.pub")
        assertTrue(checked.lines.single().selected)

        assertFalse(checked.withToggled("com.tcl.pub").lines.single().selected)
    }

    @Test
    fun `an already disabled or missing package cannot be checked`() {
        val start = state(
            "com.deja.eteint" to PackageState.DISABLED,
            "com.pas.installe" to PackageState.ABSENT,
        )

        val after = start.withToggled("com.deja.eteint").withToggled("com.pas.installe")

        assertTrue(after.selection.isEmpty())
    }

    @Test
    fun `a profile only checks its category, and only active packages`() {
        val start = RemoteState(
            lines = listOf(
                PackageRow(entry("com.a", "bloatware_tcl"), PackageState.ACTIVE),
                PackageRow(entry("com.b", "expert"), PackageState.ACTIVE),
                PackageRow(entry("com.c", "bloatware_tcl"), PackageState.DISABLED),
            ),
        )

        val after = start.withProfile(profile("bloatware_tcl"))

        assertEquals(listOf("com.a"), after.selection.map { it.entry.packageName })
    }

    @Test
    fun `a profile adds to the current selection instead of replacing it`() {
        val start = RemoteState(
            lines = listOf(
                PackageRow(entry("com.a", "expert"), PackageState.ACTIVE, selected = true),
                PackageRow(entry("com.b", "bloatware_tcl"), PackageState.ACTIVE),
            ),
        )

        val after = start.withProfile(profile("bloatware_tcl"))

        assertEquals(listOf("com.a", "com.b"), after.selection.map { it.entry.packageName })
    }

    @Test
    fun `a profile never checks an untested entry, which can still be checked by hand`() {
        val start = RemoteState(
            lines = listOf(
                PackageRow(entry("com.a"), PackageState.ACTIVE),
                PackageRow(entry("org.droidtv.welcome").copy(tested = false), PackageState.ACTIVE),
            ),
        )

        val after = start.withProfile(profile("bloatware_tcl"))

        assertEquals(listOf("com.a"), after.selection.map { it.entry.packageName })
        assertEquals(
            listOf("com.a", "org.droidtv.welcome"),
            after.withToggled("org.droidtv.welcome").selection.map { it.entry.packageName },
        )
    }

    @Test
    fun `unchecking all leaves nothing selected`() {
        val start = state("com.a" to PackageState.ACTIVE, "com.b" to PackageState.ACTIVE)
            .withToggled("com.a")
            .withToggled("com.b")

        assertTrue(start.withoutSelection().selection.isEmpty())
    }
}
