package net.jolabs40.tvslim.catalog

import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.device.unknownPackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a phone, preinstalled launcher apps can be disabled one by one; on a TV nothing changes. Data from a Pixel 9a:
 * YouTube and YouTube Music preinstalled, Spotify user-installed.
 */
class DeviceApplicationsTest {

    private val catalog = Catalog(
        categories = listOf(Category("streaming", "Streaming"), Category(DEVICE_CATEGORY, "Applications")),
        entries = listOf(PackageEntry("com.google.android.youtube.tv", "YouTube", "", "streaming")),
        protectedPackages = listOf(ProtectedPackage("com.android.settings", "Réglages")),
    )

    private val pixel = DeviceInfo(
        brand = "Google",
        model = "Pixel 9a",
        characteristics = "nosdcard",
        features = setOf(DeviceInfo.FEATURE_TOUCHSCREEN),
    )

    private val tcl = DeviceInfo(
        brand = "TCL",
        model = "Smart TV Pro",
        characteristics = "tv",
        features = setOf(DeviceInfo.FEATURE_LEANBACK),
    )

    private val system = mapOf(
        "com.google.android.youtube" to PackageState.ACTIVE,
        "com.google.android.apps.youtube.music" to PackageState.DISABLED,
        "com.android.settings" to PackageState.ACTIVE,
        "com.android.phone" to PackageState.ACTIVE,
    )

    private val menu = setOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.android.settings",
        // User-installed, so not among the system packages: left out.
        "com.spotify.music",
    )

    @Test
    fun `on a phone, preinstalled launcher apps become untested entries`() {
        val deviceCatalog = catalog.withMenuApps(pixel, system, menu)

        val added = deviceCatalog.entries.filter { it.category == DEVICE_CATEGORY }
        assertEquals(
            listOf("com.google.android.apps.youtube.music", "com.google.android.youtube"),
            added.map { it.packageName },
        )
        // Never selected by a profile, and flagged as such.
        assertTrue(added.none { it.tested })
        // The blocklist wins, and a package with no launcher icon (the phone app) is not offered.
        assertFalse(deviceCatalog.entries.any { it.packageName == "com.android.settings" || it.packageName == "com.android.phone" })
        assertFalse(deviceCatalog.entries.any { it.packageName == "com.spotify.music" })
        // No longer listed as unknown.
        assertFalse(deviceCatalog.unknownPackages(system, null).any { it.packageName == "com.google.android.youtube" })
    }

    @Test
    fun `on a TV, nothing changes`() {
        assertSame(catalog, catalog.withMenuApps(tcl, system, menu))
        // With nothing read yet the brand decides: an unread Pixel passes for a box, so nothing is added.
        assertSame(catalog, catalog.withMenuApps(DeviceInfo(brand = "Google"), system, menu))
    }

    @Test
    fun `launcher apps are parsed from the query-activities output`() {
        val lines = listOf(
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true",
            "com.google.android.youtube/com.google.android.apps.youtube.app.WatchWhileActivity",
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false",
            "com.android.settings/.Settings",
            "No activities found",
        )

        assertEquals(setOf("com.google.android.youtube", "com.android.settings"), RemoteReader.applicationsMenu(lines))
    }
}
