package net.jolabs40.tvslim.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ce que la carte « Écran d'accueil » propose et comment elle nomme ce qui est installé.
 *
 * Le launcher recommandé ne doit plus être proposé dès qu'une de ses versions est là, y compris
 * sa version de développement ; et aucun paquet ne doit pouvoir recevoir deux logos.
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
    fun `le launcher recommande reste propose tant qu'aucune de ses versions n'est installee`() {
        assertEquals(listOf(startlight), catalogue.launchersAProposer(emptyList()))
        assertEquals(listOf(startlight), catalogue.launchersAProposer(listOf("com.spocky.projengmenu")))

        assertTrue(catalogue.launchersAProposer(listOf("net.jolabs40.startlight")).isEmpty())
        assertTrue(catalogue.launchersAProposer(listOf("net.jolabs40.startlight.debug")).isEmpty())
    }

    @Test
    fun `un launcher installe se nomme et se reconnait a son logo`() {
        assertEquals("Startlight Launcher", catalogue.nomLauncher("net.jolabs40.startlight.debug"))
        assertEquals("startlight", catalogue.idLauncher("net.jolabs40.startlight.debug"))
        assertEquals("Projectivy Launcher", catalogue.nomLauncher("com.spocky.projengmenu"))
        assertEquals("projectivy", catalogue.idLauncher("com.spocky.projengmenu"))

        assertNull(catalogue.nomLauncher("com.inconnu.launcher"))
        assertNull(catalogue.idLauncher("com.inconnu.launcher"))
    }

    @Test
    fun `le catalogue livre ne donne jamais deux logos a un meme paquet`() {
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
