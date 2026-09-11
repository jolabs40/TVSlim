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
 * Les garde-fous du moteur, sous test.
 *
 * C'est le code qui peut rendre un téléviseur inutilisable : couper l'écran d'accueil sans
 * remplaçant, désactiver le service dont dépend le démarrage, ou inverser l'ordre qui donne la
 * main à un écran de récupération. Chacune de ces règles a été apprise en intervenant à la main
 * sur une vraie TCL ; elles sont vérifiées ici une par une.
 */
class MoteurDebloatTest {

    /** Exécuteur bouchon : retient les commandes reçues et renvoie ce qu'on lui dit. */
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
    fun `un paquet de la liste noire est refuse et aucune commande ne part`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultats = moteur.desactiver(
            entrees = listOf(entree("com.android.location.fused")),
            catalogue = catalogue,
            etats = mapOf("com.android.location.fused" to EtatPaquet.ACTIF),
            launchersDisponibles = true,
        )

        assertFalse(resultats.single().reussi)
        assertTrue(resultats.single().message.contains("protégé"))
        assertTrue("Aucune commande ne doit partir : ${espion.commandes}", espion.commandes.isEmpty())
    }

    @Test
    fun `l'ecran d'accueil est refuse sans launcher tiers`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())

        val resultats = moteur.desactiver(
            entrees = listOf(entree("com.google.android.apps.tv.launcherx", requiertLauncherTiers = true)),
            catalogue = catalogue,
            etats = mapOf("com.google.android.apps.tv.launcherx" to EtatPaquet.ACTIF),
            launchersDisponibles = false,
        )

        assertFalse(resultats.single().reussi)
        assertTrue(espion.commandes.isEmpty())
    }

    @Test
    fun `setupwraith tombe avant launcherx, quel que soit l'ordre de la selection`() = runTest {
        val espion = ExecuteurEspion()
        val moteur = MoteurDebloat(espion, journal())
        val launcherx = entree("com.google.android.apps.tv.launcherx", ordre = 2, requiertLauncherTiers = true)
        val setupwraith = entree("com.google.android.tungsten.setupwraith", ordre = 1, requiertLauncherTiers = true)

        moteur.desactiver(
            // Volontairement dans le mauvais ordre : c'est au moteur de le corriger.
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
    fun `un paquet absent ou deja desactive ne declenche aucune commande`() = runTest {
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
        // Déjà désactivé : c'est un succès, l'état voulu est atteint.
        assertTrue(resultats.first { it.paquet == "deja.coupe" }.reussi)
    }

    @Test
    fun `la desactivation n'utilise jamais uninstall et journalise son annulation`() = runTest {
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
    fun `une sortie inattendue est un echec, meme avec un code de retour nul`() = runTest {
        // Le gestionnaire de paquets répond parfois 0 sans rien faire : la sortie fait foi.
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
        // Un échec ne doit pas se retrouver dans la liste à restaurer.
        assertTrue(carnet.paquetsADesactivationActive().isEmpty())
    }

    @Test
    fun `la reactivation annule la desactivation dans le journal`() = runTest {
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
    fun `un reglage journalise la commande qui remet la valeur precedente`() = runTest {
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

    // --- Permissions privilégiées -----------------------------------------------------------
    //
    // C'est le seul endroit du moteur où une commande se construit à partir d'un texte saisi et
    // non du catalogue : la saisie est donc traitée comme hostile.

    @Test
    fun `une permission absente du manifeste n'est pas accordee`() = runTest {
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
    fun `une saisie qui ouvrirait une seconde commande est refusee`() = runTest {
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
    fun `accorder journalise le revoke qui l'annule`() = runTest {
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
    fun `un pm grant bavard reste un echec malgre un code nul`() = runTest {
        // `pm grant` se tait quand il réussit. Une exception Java avec un code de retour nul
        // est exactement le piège déjà rencontré sur `pm disable-user`.
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
    fun `retirer une permission ne consulte pas le manifeste`() = runTest {
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
    fun `un app-op journalise le retour a son mode precedent`() = runTest {
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
    fun `un mode d'app-op inconnu est refuse`() = runTest {
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
    fun `un mode precedent inconnu se rend en default`() = runTest {
        // Lecture impossible au moment de poser l'op : l'annulation doit rester jouable.
        val espion = ExecuteurEspion { ResultatShell(0, "") }
        val carnet = journal()
        val moteur = MoteurDebloat(espion, carnet)

        moteur.reglerAppOp("com.tcl.gallery", "GET_USAGE_STATS", "allow", modePrecedent = "")

        assertEquals(
            "cmd appops set com.tcl.gallery GET_USAGE_STATS default",
            carnet.actions.value.single().commandeAnnulation,
        )
    }
}
