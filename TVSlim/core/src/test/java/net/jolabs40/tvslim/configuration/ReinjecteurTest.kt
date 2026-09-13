package net.jolabs40.tvslim.configuration

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LauncherInstalle
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * La réinjection passe par le moteur, dans un ordre qui ne laisse jamais le téléviseur sans accueil :
 * réactiver, désactiver, puis désigner l'écran d'accueil.
 */
class ReinjecteurTest {

    /** Répond comme le téléviseur, et retient chaque commande reçue. */
    private class ExecuteurEspion : ExecuteurCommande {
        val commandes = mutableListOf<String>()

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            val paquet = commande.substringAfterLast(' ')
            return when {
                commande.startsWith("pm enable") -> ResultatShell(0, "Package $paquet new state: enabled")
                commande.startsWith("pm disable-user") -> ResultatShell(0, "Package $paquet new state: disabled-user")
                else -> ResultatShell(0, "Success")
            }
        }
    }

    private fun entree(paquet: String, ordre: Int = 100, accueil: Boolean = false) = EntreePaquet(
        paquet = paquet,
        nom = paquet,
        description = "",
        categorie = "test",
        ordre = ordre,
        requiertLauncherTiers = accueil,
    )

    private val demo = entree("com.tcl.demo")
    private val setupwraith = entree("com.google.android.tungsten.setupwraith", ordre = 1, accueil = true)
    private val launcherx = entree("com.google.android.apps.tv.launcherx", ordre = 2, accueil = true)
    private val catalogue = Catalogue(entrees = listOf(demo, setupwraith, launcherx))

    private val etats = mapOf(
        demo.paquet to EtatPaquet.DESACTIVE,
        setupwraith.paquet to EtatPaquet.ACTIF,
        launcherx.paquet to EtatPaquet.ACTIF,
    )

    private val startlight = "net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity"

    private val tcl = InfosAppareil(
        accueilActuel = "com.google.android.apps.tv.launcherx",
        composantAccueil = "com.google.android.apps.tv.launcherx/.home.HomeActivity",
        launchersTiers = listOf(LauncherInstalle("net.jolabs40.startlight.debug", "Startlight", startlight)),
    )

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun plan(accueil: ChangementAccueil?) = PlanReinjection(
        configuration = ConfigurationTv(ConfigurationTv.APPLICATION, ConfigurationTv.FORMAT, sauvegardeLe = 0),
        aReactiver = listOf(demo),
        // Volontairement à l'envers : l'ordre du catalogue doit l'emporter.
        aDesactiver = listOf(launcherx, setupwraith),
        ignores = emptyList(),
        accueil = accueil,
    )

    @Test
    fun `on reactive, on desactive dans l'ordre du catalogue, et l'accueil vient en dernier`() = runTest {
        val espion = ExecuteurEspion()
        val etapes = mutableListOf<Pair<Int, Int>>()

        val resultats = Reinjecteur(MoteurDebloat(espion, journal())).reinjecter(
            plan = plan(ChangementAccueil("net.jolabs40.startlight.debug", "Startlight", startlight)),
            catalogue = catalogue,
            etats = etats,
            infos = tcl,
            surProgression = { fait, total -> etapes += fait to total },
        )

        assertEquals(
            listOf(
                "pm enable com.tcl.demo",
                "pm disable-user --user 0 com.google.android.tungsten.setupwraith",
                "pm disable-user --user 0 com.google.android.apps.tv.launcherx",
                "cmd package set-home-activity $startlight",
            ),
            espion.commandes,
        )
        assertTrue("Tout doit réussir : $resultats", resultats.all { it.reussi })
        assertEquals(4 to 4, etapes.last())
    }

    @Test
    fun `sans launcher tiers, l'accueil d'usine reste en place malgre la sauvegarde`() = runTest {
        val espion = ExecuteurEspion()

        val resultats = Reinjecteur(MoteurDebloat(espion, journal())).reinjecter(
            plan = plan(ChangementAccueil("net.jolabs40.startlight.debug", "Startlight", composant = "")),
            catalogue = catalogue,
            etats = etats,
            infos = tcl.copy(launchersTiers = emptyList()),
        )

        assertEquals(listOf("pm enable com.tcl.demo"), espion.commandes)
        assertEquals(2, resultats.count { !it.reussi })
    }
}
