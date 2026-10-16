package net.jolabs40.tvslim.startlight

import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.RecommendedLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class StartLightHomeTest {

    private val catalog = Catalog(
        launchers = listOf(
            RecommendedLauncher(
                packageName = "net.jolabs40.startlight",
                name = "Startlight Launcher",
                description = "",
                id = "startlight",
                variants = listOf("net.jolabs40.startlight.debug"),
            ),
        ),
    )

    @Test
    fun `release and debug builds are recognized, each with its own authority`() {
        assertEquals("net.jolabs40.startlight.remote", StartLightHome.of("net.jolabs40.startlight", catalog)?.authority)
        assertEquals("net.jolabs40.startlight.debug.remote", StartLightHome.of("net.jolabs40.startlight.debug", catalog)?.authority)
    }

    @Test
    fun `another launcher, or no home read, shows no StartLight buttons`() {
        assertNull(StartLightHome.of("com.spocky.projengmenu", catalog))
        assertNull(StartLightHome.of("com.google.android.tvlauncher", catalog))
        assertNull(StartLightHome.of("", catalog))
        assertNull(StartLightHome.of("net.jolabs40.startlight", Catalog()))
    }

    @Test
    fun `a package that merely starts like StartLight is not StartLight`() {
        assertNull(StartLightHome.of("net.jolabs40.startlight.other", catalog))
        assertNull(StartLightHome.of("net.jolabs40.startlightx", catalog))
    }

    /** The embedded catalogue is what the apps read: the id and the debug variant must stay in it. */
    @Test
    fun `the bundled catalogue knows both StartLight builds`() {
        val bundled = Json { ignoreUnknownKeys = true }.decodeFromString(Catalog.serializer(), File("src/main/assets/catalogue.json").readText())
        assertEquals("net.jolabs40.startlight", StartLightHome.of("net.jolabs40.startlight", bundled)?.packageName)
        assertEquals("net.jolabs40.startlight.debug", StartLightHome.of("net.jolabs40.startlight.debug", bundled)?.packageName)
    }
}
