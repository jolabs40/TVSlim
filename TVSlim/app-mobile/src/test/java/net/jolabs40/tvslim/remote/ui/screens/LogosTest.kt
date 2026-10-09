package net.jolabs40.tvslim.remote.ui.screens

import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.device.Manufacturer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every launcher the catalogue names, and every manufacturer flagged with a logo, needs an image, or the
 * card falls back to the generic icon or the plain name. Reads the catalogue the app ships.
 */
class LogosTest {

    private val catalog: Catalog = Json { ignoreUnknownKeys = true; isLenient = true }
        .decodeFromString(Catalog.serializer(), File("../core/src/main/assets/catalogue.json").readText())

    @Test
    fun `every catalog launcher has a logo`() {
        val ids = catalog.launchers.map { it.id } + catalog.knownLaunchers.map { it.id }

        val withoutLogo = ids.filterNot { it in LOGOS_LAUNCHERS }

        assertTrue("Launchers without a logo: $withoutLogo", withoutLogo.isEmpty())
        assertTrue("The catalog must name several launchers", ids.size > 1)
    }

    @Test
    fun `every manufacturer flagged with a logo has one, and only those`() {
        val withoutLogo = Manufacturer.entries.filter { it.hasLogo && it !in MANUFACTURER_LOGOS }

        assertTrue("Manufacturers without a logo: $withoutLogo", withoutLogo.isEmpty())
        assertTrue("Logo shipped for a brand declared without one", MANUFACTURER_LOGOS.keys.all { it.hasLogo })
    }
}
