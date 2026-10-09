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

    private val startlight = LauncherRecommande(
        paquet = "net.jolabs40.startlight",
        nom = "Startlight Launcher",
        description = "",
        id = "startlight",
        variantes = listOf("net.jolabs40.startlight.debug"),
    )

    private val catalogue = Catalogue(
        launchers = listOf(startlight),
        launchersConnus = listOf(
            LauncherConnu(id = "projectivy", nom = "Projectivy Launcher", paquets = listOf("com.spocky.projengmenu")),
        ),
    )

    @Test
    fun `the recommended launcher is offered until one of its builds is installed`() {
        assertEquals(listOf(startlight), catalogue.launchersAProposer(emptyList()))
        assertEquals(listOf(startlight), catalogue.launchersAProposer(listOf("com.spocky.projengmenu")))

        assertTrue(catalogue.launchersAProposer(listOf("net.jolabs40.startlight")).isEmpty())
        assertTrue(catalogue.launchersAProposer(listOf("net.jolabs40.startlight.debug")).isEmpty())
    }

    @Test
    fun `the recommended launcher comes first, its release build before its debug build`() {
        val installes = listOf("com.spocky.projengmenu", "net.jolabs40.startlight.debug", "ca.dstudio.atvlauncher.pro", "net.jolabs40.startlight")

        assertEquals(
            listOf("net.jolabs40.startlight", "net.jolabs40.startlight.debug", "com.spocky.projengmenu", "ca.dstudio.atvlauncher.pro"),
            catalogue.recommandesDAbord(installes) { it },
        )
        assertEquals(listOf("b", "a"), Catalogue().recommandesDAbord(listOf("b", "a")) { it })
    }

    @Test
    fun `the recommended launcher website is shown without its scheme`() {
        assertEquals("startlightlauncher.com", startlight.copy(site = "https://startlightlauncher.com/").siteAffiche)
        val embarque = File("src/main/assets/catalogue.json").readText()
        val lu = Json { ignoreUnknownKeys = true }.decodeFromString(Catalogue.serializer(), embarque)
        assertEquals("https://startlightlauncher.com", lu.launcherRecommande("net.jolabs40.startlight")?.site)
    }

    @Test
    fun `an installed launcher gets its name and its logo id`() {
        assertEquals("Startlight Launcher", catalogue.nomLauncher("net.jolabs40.startlight.debug"))
        assertEquals("startlight", catalogue.idLauncher("net.jolabs40.startlight.debug"))
        assertEquals("Projectivy Launcher", catalogue.nomLauncher("com.spocky.projengmenu"))
        assertEquals("projectivy", catalogue.idLauncher("com.spocky.projengmenu"))

        assertNull(catalogue.nomLauncher("com.inconnu.launcher"))
        assertNull(catalogue.idLauncher("com.inconnu.launcher"))
    }

    @Test
    fun `the shipped catalog never gives one package two logos`() {
        val livre = Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString(Catalogue.serializer(), File("src/main/assets/catalogue.json").readText())

        val ids = livre.launchers.map { it.id } + livre.launchersConnus.map { it.id }
        assertTrue("Un launcher sans identifiant n'aurait pas de logo : $ids", ids.none { it.isBlank() })
        assertEquals("Identifiants en double : $ids", ids.size, ids.toSet().size)

        val paquets = livre.launchers.flatMap { listOf(it.paquet) + it.variantes } +
            livre.launchersConnus.flatMap { it.paquets }
        assertEquals("Paquet cité deux fois : $paquets", paquets.size, paquets.toSet().size)
    }
}
