package net.jolabs40.tvslim.remote.ui.screens

import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.Fabricant
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every launcher the catalogue names, and every manufacturer flagged with a logo, needs an image, or the
 * card falls back to the generic icon or the plain name. Reads the catalogue the app ships.
 */
class LogosTest {

    private val catalogue: Catalogue = Json { ignoreUnknownKeys = true; isLenient = true }
        .decodeFromString(Catalogue.serializer(), File("../core/src/main/assets/catalogue.json").readText())

    @Test
    fun `every catalog launcher has a logo`() {
        val ids = catalogue.launchers.map { it.id } + catalogue.launchersConnus.map { it.id }

        val sansLogo = ids.filterNot { it in LOGOS_LAUNCHERS }

        assertTrue("Launchers sans logo : $sansLogo", sansLogo.isEmpty())
        assertTrue("Le catalogue doit nommer plusieurs launchers", ids.size > 1)
    }

    @Test
    fun `every manufacturer flagged with a logo has one, and only those`() {
        val sansLogo = Fabricant.entries.filter { it.aUnLogo && it !in LOGOS_FABRICANTS }

        assertTrue("Fabricants sans logo : $sansLogo", sansLogo.isEmpty())
        assertTrue("Logo embarqué pour une marque annoncée sans logo", LOGOS_FABRICANTS.keys.all { it.aUnLogo })
    }
}
