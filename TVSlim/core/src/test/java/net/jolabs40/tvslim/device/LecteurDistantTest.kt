package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.ExecuteurCommande
import net.jolabs40.tvslim.shell.ResultatShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote state reading.
 *
 * The first test comes from a real failure on the TCL: markers started with `#`, which the shell reads as
 * a comment. The rest of the compound command was swallowed, `pm` never ran, and the companion showed an
 * empty TV with no error.
 */
class LecteurDistantTest {

    private class ExecuteurFixe(private val sortie: String, private val code: Int = 0) :
        ExecuteurCommande {
        var recue: String? = null
        override suspend fun executer(commande: String): ResultatShell {
            recue = commande
            return ResultatShell(code, sortie)
        }
    }

    @Test
    fun `the command contains no word that starts a shell comment`() {
        val motsCommentaire = LecteurDistant.COMMANDE
            .split(' ', ';')
            .map { it.trim() }
            .filter { it.startsWith("#") }

        assertTrue(
            "Ces mots feraient taire tout le reste de la ligne : $motsCommentaire",
            motsCommentaire.isEmpty(),
        )
    }

    @Test
    fun `each section is announced by its marker`() {
        val marqueurs = listOf("_D", "_E", "_P", "_B", "_M", "_H", "_L", "_U", "_T")
            .map { LecteurDistant.PREFIXE_MARQUEUR + it.removePrefix("_") }
        marqueurs.forEach { marqueur ->
            assertTrue(
                "La commande doit annoncer $marqueur",
                LecteurDistant.COMMANDE.contains("echo $marqueur"),
            )
        }
    }

    @Test
    fun `a realistic output is split correctly`() = runTest {
        val sortie = """
            @@TVSLIM_D
            package:com.tcl.gallery
            package:com.netflix.ninja
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:net.jolabs40.tvslim
            @@TVSLIM_P
            TCL
            65C89K
            14
            tcl9618-user
            @@TVSLIM_B
            TCL
            @@TVSLIM_M
            MemTotal:        2513404 kB
            MemAvailable:     628112 kB
            @@TVSLIM_H
            priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false
            com.spocky.projengmenu/.ui.home.MainActivity
            @@TVSLIM_L
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.spocky.projengmenu/.ui.home.MainActivity
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.apps.tv.launcherx/.home.HomeActivity
            priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.android.tv.settings/.system.FallbackHome
        """.trimIndent()

        val photo = LecteurDistant(ExecuteurFixe(sortie)).photographie(
            paquetsSurveilles = listOf("com.tcl.gallery", "com.spocky.projengmenu", "absent.ici"),
            paquetsDAccueil = setOf("com.google.android.apps.tv.launcherx"),
        )

        assertEquals(EtatPaquet.DESACTIVE, photo.etats["com.tcl.gallery"])
        assertEquals(EtatPaquet.ACTIF, photo.etats["com.spocky.projengmenu"])
        assertEquals(EtatPaquet.ABSENT, photo.etats["absent.ici"])

        assertEquals("TCL", photo.infos.marque)
        assertEquals("TCL", photo.infos.marqueCommerciale)
        assertEquals("65C89K", photo.infos.modele)
        assertEquals("14", photo.infos.versionAndroid)
        assertEquals(2, photo.infos.paquetsDesactives)
        assertEquals(2, photo.infos.paquetsInstalles)
        assertEquals(2454, photo.infos.memoireTotaleMo)
        assertEquals("com.spocky.projengmenu", photo.infos.accueilActuel)

        // The factory home does not count as a replacement launcher.
        assertEquals(listOf("com.spocky.projengmenu"), photo.infos.launchersTiers.map { it.paquet })
    }

    @Test
    fun `an empty retail brand does not shift any property`() = runTest {
        val sortie = """
            @@TVSLIM_P
            NVIDIA
            SHIELD Android TV
            11
            RQ1A.210105.003
            @@TVSLIM_B
            @@TVSLIM_M
            MemTotal:        3016092 kB
        """.trimIndent()

        val infos = LecteurDistant(ExecuteurFixe(sortie)).photographie(emptyList(), emptySet()).infos

        assertEquals("", infos.marqueCommerciale)
        assertEquals("NVIDIA", infos.marque)
        assertEquals("RQ1A.210105.003", infos.build)
    }

    @Test
    fun `a fallback screen is never taken for a replacement launcher`() = runTest {
        // Seen on a Shield: FallbackHome answers category.HOME with a negative priority. Counting it as a
        // replacement would let the engine disable the factory home, and the device would boot to a blank screen.
        val sortie = """
            @@TVSLIM_L
            2 activities found:
              Activity #0:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.tvlauncher/.MainActivity
              Activity #1:
                priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.tv.settings/.system.FallbackHome
        """.trimIndent()

        val photo = LecteurDistant(ExecuteurFixe(sortie)).photographie(
            paquetsSurveilles = emptyList(),
            paquetsDAccueil = setOf("com.google.android.tvlauncher"),
        )

        assertTrue(
            "Aucun launcher tiers ici : ${photo.infos.launchersTiers.map { it.paquet }}",
            photo.infos.launchersTiers.isEmpty(),
        )
    }

    @Test
    fun `a disabled factory home is found, without setup wizards or fallback screens`() = runTest {
        // From the TCL: disabled Google TV only shows up with --query-flags 512, next to provisioning,
        // a setup wizard and two fallback screens.
        val sortie = """
            @@TVSLIM_D
            package:com.google.android.apps.tv.launcherx
            package:com.google.android.tungsten.setupwraith
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:net.jolabs40.startlight.debug
            @@TVSLIM_H
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity
            @@TVSLIM_L
            com.spocky.projengmenu/.ui.home.MainActivity
            net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity
            com.android.tv.settings/.system.FallbackHome
            @@TVSLIM_U
            10 activities found:
              Activity #0:
                priority=10 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.managedprovisioning/.preprovisioning.PostEncryptionActivity
              Activity #1:
                priority=4 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.tungsten.setupwraith/.MainActivity
              Activity #2:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.HomeActivity
              Activity #3:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.VanillaModeHomeActivity
              Activity #4:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.spocky.projengmenu/.ui.home.MainActivity
              Activity #5:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity
              Activity #6:
                priority=-100 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                android/com.android.internal.app.SystemUserHomeActivity
              Activity #7:
                priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.tv.settings/.system.FallbackHome
            @@TVSLIM_T
            package:flar2.homebutton
            package:com.spocky.projengmenu
            package:net.jolabs40.startlight.debug
        """.trimIndent()

        val infos = LecteurDistant(ExecuteurFixe(sortie)).photographie(
            paquetsSurveilles = emptyList(),
            paquetsDAccueil = setOf(
                "com.google.android.tungsten.setupwraith",
                "com.google.android.apps.tv.launcherx",
                "com.google.android.tvlauncher",
            ),
        ).infos

        assertEquals(
            listOf(
                AccueilUsine(
                    paquet = "com.google.android.apps.tv.launcherx",
                    composant = "com.google.android.apps.tv.launcherx/.home.HomeActivity",
                    actif = false,
                ),
            ),
            infos.accueilsUsine,
        )
        assertEquals("net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity", infos.composantAccueil)
        // The safeguard is unchanged: third-party launchers still come from the unflagged query.
        assertEquals(
            listOf("com.spocky.projengmenu", "net.jolabs40.startlight.debug"),
            infos.launchersTiers.map { it.paquet },
        )
    }

    @Test
    fun `system packages are everything the user did not install`() = runTest {
        val sortie = """
            @@TVSLIM_D
            package:com.google.android.apps.tv.launcherx
            package:com.tcl.tv.tclhome_passive
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:com.mediatek.wwtv.tvcenter
            @@TVSLIM_T
            package:com.spocky.projengmenu
        """.trimIndent()

        val photo = LecteurDistant(ExecuteurFixe(sortie)).photographie(emptyList(), emptySet())

        assertEquals(
            mapOf(
                "com.google.android.apps.tv.launcherx" to EtatPaquet.DESACTIVE,
                "com.tcl.tv.tclhome_passive" to EtatPaquet.DESACTIVE,
                "com.mediatek.wwtv.tvcenter" to EtatPaquet.ACTIF,
            ),
            photo.paquetsSysteme,
        )

        // Without the third-party list, nothing: Projectivy would pass for a system package.
        val sansTiers = LecteurDistant(ExecuteurFixe(sortie.substringBefore("@@TVSLIM_T")))
            .photographie(emptyList(), emptySet())
        assertTrue(sansTiers.paquetsSysteme.isEmpty())
    }

    @Test
    fun `without the third-party app list, only catalogue homes count as factory homes`() = runTest {
        val sortie = """
            @@TVSLIM_D
            package:com.google.android.tvlauncher
            @@TVSLIM_U
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.tvlauncher/.MainActivity
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.spocky.projengmenu/.ui.home.MainActivity
        """.trimIndent()

        val infos = LecteurDistant(ExecuteurFixe(sortie))
            .photographie(emptyList(), setOf("com.google.android.tvlauncher"))
            .infos

        assertEquals(listOf("com.google.android.tvlauncher"), infos.accueilsUsine.map { it.paquet })
        assertFalse(infos.accueilsUsine.single().actif)
    }

    @Test
    fun `an Android that ignores the flag yields no home from its help message`() = runTest {
        val sortie = """
            @@TVSLIM_E
            package:com.google.android.tvlauncher
            @@TVSLIM_U
            Error: Unknown option: --query-flags
              -a <ACTION>/-d <DATA_URI> [-t <MIME_TYPE>]
            @@TVSLIM_T
            package:com.spocky.projengmenu
        """.trimIndent()

        val infos = LecteurDistant(ExecuteurFixe(sortie))
            .photographie(emptyList(), setOf("com.google.android.tvlauncher"))
            .infos

        assertEquals(listOf(AccueilUsine("com.google.android.tvlauncher", "", actif = true)), infos.accueilsUsine)
    }

    @Test
    fun `the Philips HOME dispatcher is not a home screen`() = runTest {
        // From a Philips Google TV TA1 (Android 14): org.droidtv.homeintentresolver receives HOME at priority 100,
        // above Google TV, and decides where the key goes.
        val sortie = """
            @@TVSLIM_D
            @@TVSLIM_E
            package:com.google.android.apps.tv.launcherx
            package:org.droidtv.homeintentresolver
            @@TVSLIM_H
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.apps.tv.launcherx/.home.HomeActivity
            @@TVSLIM_L
            com.google.android.apps.tv.launcherx/.home.HomeActivity
            @@TVSLIM_U
            8 activities found:
              Activity #0:
                priority=100 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                org.droidtv.homeintentresolver/.HomeActivity
              Activity #1:
                priority=10 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.managedprovisioning/.preprovisioning.PostEncryptionActivity
              Activity #2:
                priority=4 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.tungsten.setupwraith/.MainActivity
              Activity #3:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.HomeActivity
              Activity #7:
                priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.tv.settings/.system.FallbackHome
            @@TVSLIM_T
            package:com.netflix.ninja
        """.trimIndent()

        val infos = LecteurDistant(ExecuteurFixe(sortie)).photographie(
            paquetsSurveilles = emptyList(),
            paquetsDAccueil = setOf("com.google.android.tungsten.setupwraith", "com.google.android.apps.tv.launcherx"),
        ).infos

        assertEquals(listOf("com.google.android.apps.tv.launcherx"), infos.accueilsUsine.map { it.paquet })
        assertTrue(infos.launchersTiers.isEmpty())
    }

    @Test
    fun `the current home can be read alone, and a failed read returns nothing`() = runTest {
        val sortie = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
            "com.spocky.projengmenu/.ui.home.HomeActivity"
        assertEquals("com.spocky.projengmenu", LecteurDistant(ExecuteurFixe(sortie)).accueilActuel())
        assertEquals("", LecteurDistant(ExecuteurFixe("", code = -1)).accueilActuel())
    }

    @Test
    fun `a failed command does not produce fake data`() = runTest {
        val photo = LecteurDistant(ExecuteurFixe("", code = 1)).photographie(
            paquetsSurveilles = listOf("com.tcl.gallery"),
            paquetsDAccueil = emptySet(),
        )

        assertEquals(InfosAppareil.VIDE, photo.infos)
        assertTrue(photo.etats.isEmpty())
    }

    @Test
    fun `an empty output does not pass for a TV with no packages`() = runTest {
        // What the command swallowed by the comment returned: an empty success.
        val photo = LecteurDistant(ExecuteurFixe("")).photographie(
            paquetsSurveilles = listOf("com.tcl.gallery"),
            paquetsDAccueil = emptySet(),
        )

        // The package is reported absent for lack of anything better, but nothing may suggest a healthy TV.
        assertEquals(EtatPaquet.ABSENT, photo.etats["com.tcl.gallery"])
        assertEquals(0, photo.infos.paquetsInstalles)
        assertFalse("Aucune propriété ne doit être inventée", photo.infos.modele.isNotBlank())
    }
    @Test
    fun `requested permissions are told apart from those actually granted`() = runTest {
        // Real `dumpsys package` excerpt: sections are separated only by indentation, and "declared permissions"
        // lists what the app defines for others, not what it requests.
        val executeur = ExecuteurFixe(
            """
            Permissions:
              Permission [net.jolabs40.hippietv.permission.RECEVOIR] (7f3):
                sourcePackage=net.jolabs40.hippietv.launcher.debug
            Packages:
              Package [net.jolabs40.hippietv.launcher.debug] (a1b2c3):
                userId=10123
                declared permissions:
                  net.jolabs40.hippietv.permission.RECEVOIR: prot=signature, INSTALLED
                requested permissions:
                  android.permission.INTERNET
                  android.permission.DUMP
                  android.permission.POST_NOTIFICATIONS
                install permissions:
                  android.permission.INTERNET: granted=true
                  android.permission.DUMP: granted=false
                User 0: ceDataInode=123 installed=true hidden=false
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]
                    android.permission.READ_MEDIA_IMAGES: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED ]
            """.trimIndent(),
        )

        val lues = LecteurDistant(executeur).permissions("net.jolabs40.hippietv.launcher.debug")

        assertTrue(lues.paquetTrouve)
        assertTrue(lues.estDeclaree("android.permission.DUMP"))
        assertFalse(
            "DUMP est demandée mais pas encore accordée",
            lues.estAccordee("android.permission.DUMP"),
        )
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_MEDIA_IMAGES",
            ),
            lues.accordees,
        )
        assertFalse(
            "Une permission que l'application définit n'est pas une permission qu'elle demande",
            lues.estDeclaree("net.jolabs40.hippietv.permission.RECEVOIR"),
        )
    }

    @Test
    fun `a suspicious package name never reaches the shell`() = runTest {
        val executeur = ExecuteurFixe("")

        val lues = LecteurDistant(executeur).permissions("com.tcl.gallery; reboot")

        assertFalse(lues.paquetTrouve)
        assertEquals(null, executeur.recue)
    }
    @Test
    fun `an app-op mode is read in all three output forms`() = runTest {
        // The three outputs seen on a real device.
        val pose = LecteurDistant(ExecuteurFixe("GET_USAGE_STATS: allow; time=+13m59s344ms ago"))
        assertEquals("allow", pose.modeAppOp("com.exemple", "GET_USAGE_STATS"))

        val jamaisPose = LecteurDistant(ExecuteurFixe("No operations." + System.lineSeparator() + "Default mode: default"))
        assertEquals("default", jamaisPose.modeAppOp("com.exemple", "GET_USAGE_STATS"))

        val enErreur = LecteurDistant(ExecuteurFixe("Error: No UID for com.exemple in user 0"))
        assertEquals("", enErreur.modeAppOp("com.exemple", "GET_USAGE_STATS"))
    }

    @Test
    fun `an app-op with a suspicious name never reaches the shell`() = runTest {
        val executeur = ExecuteurFixe("GET_USAGE_STATS: allow")

        val mode = LecteurDistant(executeur).modeAppOp("com.exemple", "GET_USAGE_STATS; reboot")

        assertEquals("", mode)
        assertEquals(null, executeur.recue)
    }
}
