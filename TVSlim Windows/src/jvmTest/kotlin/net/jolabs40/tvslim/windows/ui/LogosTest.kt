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
 * Chaque launcher que le catalogue sait nommer, et chaque fabricant annoncé avec un logo, doit avoir
 * son image : sans elle, la carte retomberait sur l'icône neutre ou sur le nom écrit.
 */
class LogosTest {

    @Test
    fun `chaque launcher du catalogue a son logo`() = runTest {
        val catalogue = CatalogueRepository { "en" }.catalogue()
        val ids = catalogue.launchers.map { it.id } + catalogue.launchersConnus.map { it.id }

        val sansLogo = ids.filterNot { it in LOGOS_LAUNCHERS }

        assertTrue("Launchers sans logo : $sansLogo", sansLogo.isEmpty())
        assertTrue("Le catalogue doit nommer plusieurs launchers", ids.size > 1)
    }

    @Test
    fun `chaque fabricant annonce avec un logo en a un, et eux seuls`() {
        val sansLogo = Fabricant.entries.filter { it.aUnLogo && it !in LOGOS_FABRICANTS }

        assertTrue("Fabricants sans logo : $sansLogo", sansLogo.isEmpty())
        assertTrue("Logo embarqué pour une marque annoncée sans logo", LOGOS_FABRICANTS.keys.all { it.aUnLogo })
    }

    @Test
    fun `dix fabricants de televiseurs et cinq de box ont leur logo`() {
        val televiseurs = LOGOS_FABRICANTS.keys.filter { it.type == TypeAppareil.TELEVISEUR }
        // Xiaomi fait les deux : ses box (Mi Box, TV Stick) comptent parmi les cinq.
        val box = LOGOS_FABRICANTS.keys.filter { it.type == TypeAppareil.BOX || it == Fabricant.XIAOMI }

        assertEquals(10, televiseurs.size)
        assertEquals(5, box.size)
    }
}
