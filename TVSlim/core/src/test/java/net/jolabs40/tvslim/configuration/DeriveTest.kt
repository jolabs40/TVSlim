package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.LauncherRecommande
import net.jolabs40.tvslim.catalog.PaquetProtege
import net.jolabs40.tvslim.device.AccueilUsine
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LauncherInstalle
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.TypeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La dérive : ce que le journal a coupé et que le téléviseur a rallumé. Elle ne doit rien proposer
 * sur un téléviseur conforme, ni prendre un choix de la personne pour un accident.
 */
class DeriveTest {

    private fun entree(paquet: String, ordre: Int = 100, accueil: Boolean = false) = EntreePaquet(
        paquet = paquet,
        nom = "Nom de $paquet",
        description = "",
        categorie = "test",
        ordre = ordre,
        requiertLauncherTiers = accueil,
    )

    private val catalogue = Catalogue(
        entrees = listOf(
            entree("com.tcl.pub"),
            entree("com.tcl.demo"),
            entree(SETUPWRAITH, ordre = 1, accueil = true),
            entree(LAUNCHERX, ordre = 2, accueil = true),
        ),
        proteges = listOf(PaquetProtege(PROTEGE, "Boucle de redémarrage.")),
        launchers = listOf(
            LauncherRecommande(paquet = STARTLIGHT, nom = "Startlight Launcher", description = "", id = "startlight"),
        ),
    )

    private fun action(type: TypeAction, cible: String, reussi: Boolean = true) = ActionJournal(
        horodatage = 0,
        type = type,
        cible = cible,
        libelle = "",
        commandeAnnulation = "",
        reussi = reussi,
    )

    /** Le journal de la TCL : publicité, Google TV et son assistant coupés, Startlight désigné. */
    private val journal = listOf(
        action(TypeAction.DESACTIVATION, "com.tcl.pub"),
        action(TypeAction.DESACTIVATION, SETUPWRAITH),
        action(TypeAction.DESACTIVATION, LAUNCHERX),
        action(TypeAction.ACCUEIL, "$STARTLIGHT/.HomeActivity"),
    )

    /** Le téléviseur tel que TV Slim l'a laissé. */
    private val conforme = mapOf(
        "com.tcl.pub" to EtatPaquet.DESACTIVE,
        "com.tcl.demo" to EtatPaquet.ACTIF,
        SETUPWRAITH to EtatPaquet.DESACTIVE,
        LAUNCHERX to EtatPaquet.DESACTIVE,
    )

    private val tcl = InfosAppareil(
        accueilActuel = STARTLIGHT,
        composantAccueil = "$STARTLIGHT/.HomeActivity",
        launchersTiers = listOf(LauncherInstalle(STARTLIGHT, "Startlight", "$STARTLIGHT/.HomeActivity")),
        accueilsUsine = listOf(AccueilUsine(LAUNCHERX, "$LAUNCHERX/.home.HomeActivity", actif = false)),
    )

    /** Après la mise à jour : tout ce qui était coupé revient, et Google TV reprend l'accueil. */
    private val apresMiseAJour = conforme.mapValues { EtatPaquet.ACTIF }
    private val tclApresMiseAJour = tcl.copy(
        accueilActuel = LAUNCHERX,
        composantAccueil = "$LAUNCHERX/.home.HomeActivity",
        accueilsUsine = listOf(AccueilUsine(LAUNCHERX, "$LAUNCHERX/.home.HomeActivity", actif = true)),
    )

    @Test
    fun `un televiseur tel que TV Slim l'a laisse ne derive pas`() {
        assertNull(catalogue.planDeDerive(journal, conforme, tcl))
    }

    @Test
    fun `sans journal il n'y a rien a comparer`() {
        assertNull(catalogue.planDeDerive(emptyList(), apresMiseAJour, tclApresMiseAJour))
    }

    @Test
    fun `apres une mise a jour les paquets rallumes et l'accueil sont a reprendre`() {
        val plan = checkNotNull(catalogue.planDeDerive(journal, apresMiseAJour, tclApresMiseAJour))

        assertEquals(listOf("com.tcl.pub", SETUPWRAITH, LAUNCHERX).sorted(), plan.aDesactiver.map { it.paquet }.sorted())
        assertTrue(plan.aReactiver.isEmpty())
        assertEquals("$STARTLIGHT/.HomeActivity", plan.accueil?.composant)
        assertEquals(4, plan.nombreActions)
    }

    @Test
    fun `un paquet restaure depuis TV Slim n'est pas une derive`() {
        val restaure = journal + action(TypeAction.REACTIVATION, "com.tcl.pub")
        val etats = conforme + ("com.tcl.pub" to EtatPaquet.ACTIF)

        assertNull(catalogue.planDeDerive(restaure, etats, tcl))
    }

    @Test
    fun `une desactivation ratee n'est pas un etat voulu`() {
        val rate = listOf(action(TypeAction.DESACTIVATION, "com.tcl.demo", reussi = false))

        assertNull(catalogue.planDeDerive(rate, conforme, tcl))
    }

    @Test
    fun `un autre launcher choisi depuis est un choix et non une derive`() {
        val projectivy = tcl.copy(accueilActuel = PROJECTIVY, composantAccueil = "$PROJECTIVY/.Main")

        assertNull(catalogue.planDeDerive(journal, conforme, projectivy))
    }

    @Test
    fun `le selecteur d'Android compte comme un accueil retombe`() {
        val selecteur = tcl.copy(accueilActuel = "android", composantAccueil = "android/.ResolverActivity")
        val plan = checkNotNull(catalogue.planDeDerive(journal, conforme, selecteur))

        assertTrue(plan.aDesactiver.isEmpty())
        assertEquals("$STARTLIGHT/.HomeActivity", plan.accueil?.composant)
    }

    @Test
    fun `un launcher desinstalle depuis ne se repropose pas`() {
        val sansStartlight = tclApresMiseAJour.copy(launchersTiers = emptyList())
        val plan = checkNotNull(catalogue.planDeDerive(journal, apresMiseAJour, sansStartlight))

        assertNull(plan.accueil)
    }

    @Test
    fun `un paquet absent ou protege n'est jamais propose`() {
        val avecProtege = journal + action(TypeAction.DESACTIVATION, PROTEGE) +
            action(TypeAction.DESACTIVATION, "com.tcl.retire")
        val etats = conforme + (PROTEGE to EtatPaquet.ACTIF)

        assertNull(catalogue.planDeDerive(avecProtege, etats, tcl))
    }

    private companion object {
        const val SETUPWRAITH = "com.google.android.tungsten.setupwraith"
        const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
        const val STARTLIGHT = "net.jolabs40.startlight"
        const val PROJECTIVY = "com.spocky.projengmenu"
        const val PROTEGE = "com.android.location.fused"
    }
}
