package net.jolabs40.tvslim.remote.ui.screens

import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.Fabricant
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chaque launcher que le catalogue sait nommer, et chaque fabricant annoncé avec un logo, doit avoir
 * son image : sans elle, la carte retomberait sur l'icône neutre ou sur le nom écrit. Le catalogue lu
 * est celui que l'application embarque.
 */
class LogosTest {

    private val catalogue: Catalogue = Json { ignoreUnknownKeys = true; isLenient = true }
        .decodeFromString(Catalogue.serializer(), File("../core/src/main/assets/catalogue.json").readText())

    @Test
    fun `chaque launcher du catalogue a son logo`() {
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
}
