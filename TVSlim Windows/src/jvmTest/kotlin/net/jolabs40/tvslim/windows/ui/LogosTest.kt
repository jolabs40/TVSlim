package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.device.Manufacturer
import net.jolabs40.tvslim.device.DeviceType
import net.jolabs40.tvslim.windows.ui.components.MANUFACTURER_LOGOS
import net.jolabs40.tvslim.windows.ui.components.LOGOS_LAUNCHERS
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
        val catalog = CatalogRepository { "en" }.catalog()
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

    @Test
    fun `ten TV makers and five box makers have a logo`() {
        val tvs = MANUFACTURER_LOGOS.keys.filter { it.type == DeviceType.TV }
        // Xiaomi makes both; its boxes (Mi Box, TV Stick) count among the five.
        val box = MANUFACTURER_LOGOS.keys.filter { it.type == DeviceType.BOX || it == Manufacturer.XIAOMI }

        assertEquals(10, tvs.size)
        assertEquals(5, box.size)
    }
}
