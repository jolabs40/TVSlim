package net.jolabs40.tvslim.moteur

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.PaquetProtege
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine's guardrails. This code can leave a TV unusable: disabling the home screen with no
 * replacement, disabling a service boot depends on, or disabling in the order that hands control
 * to a recovery screen. Each rule was learned by hand on a real TCL.
 */
class MoteurDebloatTest {

    /** Fake executor that records commands and returns canned replies. */
    private class ExecuteurEspion(
        private val reponse: (String) -> ResultatShell = { ResultatShell(0, "new state: disabled-user") },
    ) : ExecuteurCommande {
        val commandes = mutableListOf<String>()

        override suspend fun executer(commande: String): ResultatShell {
            commandes += commande
            return reponse(commande)
        }
    }

    private fun journal() = JournalRepository(
        File.createTempFile("journal", ".json").also { it.delete() },
    )

    private fun entree(
        paquet: String,
        ordre: Int = 100,
        requiertLauncherTiers: Boolean = false,
    ) = EntreePaquet(
        paquet = paquet,
        nom = paquet,
        description = "",
        categorie = "test",
        ordre = ordre,
        requiertLauncherTiers = requiertLauncherTiers,
    )

    private val catalogue = Catalogue(
        proteges = listOf(
            PaquetProtege("com.android.location.fused", "Boucle de redémarrage."),
        ),
    )

    @Test
    fun `a blacklisted package is refused and no command is sent`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultats = moteur.desactiver(
            entrees = listOf(entree("com.android.location.fused")),
            catalogue = catalogue,
            etats = mapOf("com.android.location.fused" to EtatPaquet.ACTIF),
            launchersDisponibles = true,
        )

        assertFalse(resultats.single().reussi)
        assertTrue(resultats.single().motif is MotifMoteur.Protege)
        assertTrue("Aucune commande ne doit partir : ${espion.commandes}", espion.commandes.isEmpty())
    }

    @Test
    fun `the home screen is refused without a third-party launcher`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultats = moteur.desactiver(
            entrees = listOf(entree("com.google.android.apps.tv.launcherx", requiertLauncherTiers = true)),
            catalogue = catalogue,
            etats = mapOf("com.google.android.apps.tv.launcherx" to EtatPaquet.ACTIF),
            launchersDisponibles = false,
        )

        assertFalse(resultats.single().reussi)
        assertEquals(MotifMoteur.SansLauncherTiers, resultats.single().motif)
        assertTrue(espion.commandes.isEmpty())
    }

    @Test
    fun `setupwraith is disabled before launcherx, whatever the selection order`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())
        val launcherx = entree("com.google.android.apps.tv.launcherx", ordre = 2, requiertLauncherTiers = true)
        val setupwraith = entree("com.google.android.tungsten.setupwraith", ordre = 1, requiertLauncherTiers = true)

        moteur.desactiver(
            // Deliberately in the wrong order: the engine must fix it.
            entrees = listOf(launcherx, setupwraith),
            catalogue = catalogue,
            etats = mapOf(
                launcherx.paquet to EtatPaquet.ACTIF,
                setupwraith.paquet to EtatPaquet.ACTIF,
            ),
            launchersDisponibles = true,
        )

        assertEquals(2, espion.commandes.size)
        assertTrue(
            "setupwraith doit passer en premier : ${espion.commandes}",
            espion.commandes[0].contains("setupwraith"),
        )
        assertTrue(espion.commandes[1].contains("launcherx"))
    }

    @Test
    fun `a missing or already disabled package triggers no command`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultats = moteur.desactiver(
            entrees = listOf(entree("absent.du.televiseur"), entree("deja.coupe")),
            catalogue = catalogue,
            etats = mapOf(
                "absent.du.televiseur" to EtatPaquet.ABSENT,
                "deja.coupe" to EtatPaquet.DESACTIVE,
            ),
            launchersDisponibles = true,
        )

        assertTrue(espion.commandes.isEmpty())
        assertFalse(resultats.first { it.paquet == "absent.du.televiseur" }.reussi)
        // Already disabled counts as success: the target state is reached.
        assertTrue(resultats.first { it.paquet == "deja.coupe" }.reussi)
    }

    @Test
    fun `disabling never uses uninstall and logs its undo command`() = runTest {
        val espion = ExecuteurEspion()
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        moteur.desactiver(
            entrees = listOf(entree("com.tcl.gallery")),
            catalogue = catalogue,
            etats = mapOf("com.tcl.gallery" to EtatPaquet.ACTIF),
            launchersDisponibles = true,
        )

        assertEquals("pm disable-user --user 0 com.tcl.gallery", espion.commandes.single())
        assertFalse("Aucun uninstall, jamais", espion.commandes.any { it.contains("uninstall") })

        val action = carnet.actions.value.single()
        assertEquals(TypeAction.DESACTIVATION, action.type)
        assertEquals("pm enable com.tcl.gallery", action.commandeAnnulation)
        assertTrue(action.reussi)
        assertEquals(listOf("com.tcl.gallery"), carnet.paquetsADesactivationActive())
    }

    @Test
    fun `unexpected output is a failure, even with exit code zero`() = runTest {
        // The package manager sometimes exits 0 without doing anything; the output is what counts.
        val espion = ExecuteurEspion { ResultatShell(0, "Success") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultats = moteur.desactiver(
            entrees = listOf(entree("com.tcl.gallery")),
            catalogue = catalogue,
            etats = mapOf("com.tcl.gallery" to EtatPaquet.ACTIF),
            launchersDisponibles = true,
        )

        assertFalse(resultats.single().reussi)
        assertFalse(carnet.actions.value.single().reussi)
        // A failure must not end up in the restore list.
        assertTrue(carnet.paquetsADesactivationActive().isEmpty())
    }

    @Test
    fun `re-enabling undoes the disable in the log`() = runTest {
        val espion = ExecuteurEspion { commande ->
            if (commande.startsWith("pm enable")) {
                ResultatShell(0, "new state: enabled")
            } else {
                ResultatShell(0, "new state: disabled-user")
            }
        }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        moteur.desactiver(
            entrees = listOf(entree("com.tcl.gallery")),
            catalogue = catalogue,
            etats = mapOf("com.tcl.gallery" to EtatPaquet.ACTIF),
            launchersDisponibles = true,
        )
        assertEquals(listOf("com.tcl.gallery"), carnet.paquetsADesactivationActive())

        moteur.reactiver(listOf("com.tcl.gallery"))

        assertTrue(
            "Le paquet réactivé sort de la liste à restaurer",
            carnet.paquetsADesactivationActive().isEmpty(),
        )
        assertEquals(2, carnet.actions.value.size)
    }

    @Test
    fun `a setting logs the command that restores the previous value`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        moteur.ecrireReglage(
            cle = "low_power_standby_enabled",
            portee = "global",
            nom = "Veille",
            valeur = "0",
            valeurPrecedente = "1",
        )

        assertEquals("settings put global low_power_standby_enabled 0", espion.commandes.single())
        assertEquals(
            "settings put global low_power_standby_enabled 1",
            carnet.actions.value.single().commandeAnnulation,
        )
    }

    // --- Privileged permissions -------------------------------------------------------------
    //
    // The only engine commands built from typed input rather than the catalogue, so the input
    // is treated as hostile.

    @Test
    fun `a permission missing from the manifest is not granted`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultat = moteur.accorderPermission(
            paquet = "net.jolabs40.hippietv.launcher.debug",
            permission = "android.permission.DUMP",
            permissionsDeclarees = setOf("android.permission.INTERNET"),
        )

        assertFalse(resultat.reussi)
        assertTrue("Rien ne part vers le téléviseur", espion.commandes.isEmpty())
        assertTrue("Un refus ne se journalise pas", carnet.actions.value.isEmpty())
    }

    @Test
    fun `input that would start a second command is refused`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val moteur = MoteurDebloat(espion, journal())

        val resultat = moteur.accorderPermission(
            paquet = "com.tcl.gallery; reboot",
            permission = "android.permission.DUMP",
            permissionsDeclarees = setOf("android.permission.DUMP"),
        )

        assertFalse(resultat.reussi)
        assertTrue("Le point-virgule ne doit jamais atteindre le shell", espion.commandes.isEmpty())
    }

    @Test
    fun `granting logs the revoke that undoes it`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultat = moteur.accorderPermission(
            paquet = "net.jolabs40.hippietv.launcher.debug",
            permission = "android.permission.DUMP",
            permissionsDeclarees = setOf("android.permission.DUMP"),
        )

        assertTrue(resultat.message, resultat.reussi)
        assertEquals(
            "pm grant net.jolabs40.hippietv.launcher.debug android.permission.DUMP",
            espion.commandes.single(),
        )
        assertEquals(
            "pm revoke net.jolabs40.hippietv.launcher.debug android.permission.DUMP",
            carnet.actions.value.single().commandeAnnulation,
        )
        assertEquals(
            mapOf(
                "net.jolabs40.hippietv.launcher.debug android.permission.DUMP" to
                    "pm revoke net.jolabs40.hippietv.launcher.debug android.permission.DUMP",
            ),
            carnet.annulationsDesPermissions(),
        )
    }

    @Test
    fun `a chatty pm grant is still a failure despite exit code zero`() = runTest {
        // `pm grant` prints nothing on success. A Java exception with exit code 0 is the same
        // trap as with `pm disable-user`.
        val espion = ExecuteurEspion {
            ResultatShell(0, "java.lang.SecurityException: Permission is not a changeable")
        }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultat = moteur.accorderPermission(
            paquet = "com.tcl.gallery",
            permission = "android.permission.DUMP",
            permissionsDeclarees = setOf("android.permission.DUMP"),
        )

        assertFalse("Une sortie inattendue reste un échec", resultat.reussi)
        assertFalse(carnet.actions.value.single().reussi)
    }

    @Test
    fun `revoking a permission does not check the manifest`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultat = moteur.retirerPermission("com.tcl.gallery", "android.permission.DUMP")

        assertTrue(resultat.reussi)
        assertEquals("pm revoke com.tcl.gallery android.permission.DUMP", espion.commandes.single())
        assertEquals(
            "pm grant com.tcl.gallery android.permission.DUMP",
            carnet.actions.value.single().commandeAnnulation,
        )
    }
    @Test
    fun `an app-op logs the return to its previous mode`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultat = moteur.reglerAppOp(
            paquet = "net.jolabs40.hippietv.launcher.debug",
            appOp = "GET_USAGE_STATS",
            mode = "allow",
            modePrecedent = "ignore",
        )

        assertTrue(resultat.message, resultat.reussi)
        assertEquals(
            "cmd appops set net.jolabs40.hippietv.launcher.debug GET_USAGE_STATS allow",
            espion.commandes.single(),
        )
        assertEquals(
            "cmd appops set net.jolabs40.hippietv.launcher.debug GET_USAGE_STATS ignore",
            carnet.actions.value.single().commandeAnnulation,
        )
    }

    @Test
    fun `an unknown app-op mode is refused`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val moteur = MoteurDebloat(espion, journal())

        val resultat = moteur.reglerAppOp(
            paquet = "com.tcl.gallery",
            appOp = "GET_USAGE_STATS",
            mode = "autorise",
            modePrecedent = "default",
        )

        assertFalse(resultat.reussi)
        assertTrue(espion.commandes.isEmpty())
    }

    @Test
    fun `an unknown previous mode is restored as default`() = runTest {
        // The previous mode could not be read; the undo command must still be runnable.
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        moteur.reglerAppOp("com.tcl.gallery", "GET_USAGE_STATS", "allow", modePrecedent = "")

        assertEquals(
            "cmd appops set com.tcl.gallery GET_USAGE_STATS default",
            carnet.actions.value.single().commandeAnnulation,
        )
    }

    // --- Targets read from device output -------------------------------------------------
    //
    // These values come from `dumpsys` and `cmd package` output, which Android constrains,
    // but they are validated like every other engine target anyway.

    @Test
    fun `a malformed home component is refused and no command is sent`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultat = moteur.definirAccueil(
            composant = "com.exemple/.Main; rm -rf /sdcard",
            ancienAccueil = "com.tcl.launcher/.Home",
        )

        assertFalse(resultat.reussi)
        assertTrue(espion.commandes.isEmpty())
    }

    @Test
    fun `a malformed previous home is refused too, since it is replayed from the log`() = runTest {
        val espion = ExecuteurEspion()
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        val resultat = moteur.definirAccueil(
            composant = "com.spocky.projengmenu/.MainActivity",
            ancienAccueil = "n'importe quoi",
        )

        assertFalse(resultat.reussi)
        assertTrue(espion.commandes.isEmpty())
        assertTrue(carnet.actions.value.isEmpty())
    }

    @Test
    fun `a well-formed component goes through`() = runTest {
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val moteur = MoteurDebloat(espion, journal())

        moteur.definirAccueil("com.spocky.projengmenu/.MainActivity", "com.tcl.launcher/.Home")

        assertEquals(
            "cmd package set-home-activity com.spocky.projengmenu/.MainActivity",
            espion.commandes.single(),
        )
    }

    @Test
    fun `force-stopping a suspicious process name is refused`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        // This name comes from a regex over `dumpsys meminfo` output.
        val resultat = moteur.forcerArret("com.tcl.gallery; reboot")

        assertFalse(resultat.reussi)
        assertTrue(espion.commandes.isEmpty())
    }

    @Test
    fun `opening a store listing also validates the package name`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultat = moteur.ouvrirFicheBoutique("com.spocky.projengmenu&id=autre")

        assertFalse(resultat.reussi)
        assertTrue(espion.commandes.isEmpty())
    }
}
