package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.device.Fabricant
import net.jolabs40.tvslim.device.TypeAppareil
import net.jolabs40.tvslim.windows.ui.composants.LOGOS_FABRICANTS
import net.jolabs40.tvslim.windows.ui.composants.LOGOS_LAUNCHERS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every launcher the catalogue names, and every manufacturer flagged with a logo, needs an image, or the card falls
 * back to the generic icon or the plain name.
 */
class LogosTest {

    @Test
    fun `every catalog launcher has a logo`() = runTest {
        val catalogue = CatalogueRepository { "en" }.catalogue()
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

    @Test
    fun `ten TV makers and five box makers have a logo`() {
        val televiseurs = LOGOS_FABRICANTS.keys.filter { it.type == TypeAppareil.TELEVISEUR }
        // Xiaomi makes both; its boxes (Mi Box, TV Stick) count among the five.
        val box = LOGOS_FABRICANTS.keys.filter { it.type == TypeAppareil.BOX || it == Fabricant.XIAOMI }

        assertEquals(10, televiseurs.size)
        assertEquals(5, box.size)
    }
}
