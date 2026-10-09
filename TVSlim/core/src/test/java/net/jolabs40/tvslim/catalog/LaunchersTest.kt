package net.jolabs40.tvslim.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the home screen card offers, and how it names installed launchers.
 *
 * The recommended launcher is no longer offered once any of its builds is installed, debug included, and no
 * package may get two logos.
 */
class LaunchersTest {

    private val startlight = RecommendedLauncher(
        packageName = "net.jolabs40.startlight",
        name = "Startlight Launcher",
        description = "",
        id = "startlight",
        variants = listOf("net.jolabs40.startlight.debug"),
    )

    private val catalog = Catalog(
        launchers = listOf(startlight),
        knownLaunchers = listOf(
            KnownLauncher(id = "projectivy", name = "Projectivy Launcher", packages = listOf("com.spocky.projengmenu")),
        ),
    )

    @Test
    fun `the recommended launcher is offered until one of its builds is installed`() {
        assertEquals(listOf(startlight), catalog.launchersToOffer(emptyList()))
        assertEquals(listOf(startlight), catalog.launchersToOffer(listOf("com.spocky.projengmenu")))

        assertTrue(catalog.launchersToOffer(listOf("net.jolabs40.startlight")).isEmpty())
        assertTrue(catalog.launchersToOffer(listOf("net.jolabs40.startlight.debug")).isEmpty())
    }

    @Test
    fun `the recommended launcher comes first, its release build before its debug build`() {
        val installed = listOf("com.spocky.projengmenu", "net.jolabs40.startlight.debug", "ca.dstudio.atvlauncher.pro", "net.jolabs40.startlight")

        assertEquals(
            listOf("net.jolabs40.startlight", "net.jolabs40.startlight.debug", "com.spocky.projengmenu", "ca.dstudio.atvlauncher.pro"),
            catalog.recommendedFirst(installed) { it },
        )
        assertEquals(listOf("b", "a"), Catalog().recommendedFirst(listOf("b", "a")) { it })
    }

    @Test
    fun `the recommended launcher website is shown without its scheme`() {
        assertEquals("startlightlauncher.com", startlight.copy(site = "https://startlightlauncher.com/").displayedSite)
        val bundled = File("src/main/assets/catalogue.json").readText()
        val justRead = Json { ignoreUnknownKeys = true }.decodeFromString(Catalog.serializer(), bundled)
        assertEquals("https://startlightlauncher.com", justRead.recommendedLauncher("net.jolabs40.startlight")?.site)
    }

    @Test
    fun `an installed launcher gets its name and its logo id`() {
        assertEquals("Startlight Launcher", catalog.launcherName("net.jolabs40.startlight.debug"))
        assertEquals("startlight", catalog.launcherId("net.jolabs40.startlight.debug"))
        assertEquals("Projectivy Launcher", catalog.launcherName("com.spocky.projengmenu"))
        assertEquals("projectivy", catalog.launcherId("com.spocky.projengmenu"))

        assertNull(catalog.launcherName("com.unknown.launcher"))
        assertNull(catalog.launcherId("com.unknown.launcher"))
    }

    @Test
    fun `the shipped catalog never gives one package two logos`() {
        val shipped = Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(Catalog.serializer(), File("src/main/assets/catalogue.json").readText())

        val ids = shipped.launchers.map { it.id } + shipped.knownLaunchers.map { it.id }
        assertTrue("A launcher without an id would have no logo: $ids", ids.none { it.isBlank() })
        assertEquals("Duplicate ids: $ids", ids.size, ids.toSet().size)

        val packages = shipped.launchers.flatMap { listOf(it.packageName) + it.variants } +
            shipped.knownLaunchers.flatMap { it.packages }
        assertEquals("Package listed twice: $packages", packages.size, packages.toSet().size)
    }
}
