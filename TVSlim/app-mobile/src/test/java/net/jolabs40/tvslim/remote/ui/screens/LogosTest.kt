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

        assertTrue("Launchers sans logo : $withoutLogo", withoutLogo.isEmpty())
        assertTrue("Le catalogue doit nommer plusieurs launchers", ids.size > 1)
    }

    @Test
    fun `every manufacturer flagged with a logo has one, and only those`() {
        val withoutLogo = Manufacturer.entries.filter { it.hasLogo && it !in MANUFACTURER_LOGOS }

        assertTrue("Fabricants sans logo : $withoutLogo", withoutLogo.isEmpty())
        assertTrue("Logo embarqué pour une marque annoncée sans logo", MANUFACTURER_LOGOS.keys.all { it.hasLogo })
    }
}
