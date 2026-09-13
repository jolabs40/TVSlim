package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.catalog.LauncherConnu
import net.jolabs40.tvslim.catalog.LauncherRecommande
import net.jolabs40.tvslim.catalog.PaquetProtege
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que le catalogue ignore : le reconnaître, le ranger par origine, et en tirer un inventaire qu'on
 * peut transmettre. Rien ici ne propose de désactiver quoi que ce soit.
 */
class PaquetsInconnusTest {

    @Test
    fun `l'origine d'un paquet se devine a son nom et a la marque de l'appareil`() {
        assertEquals(OriginePaquet.ANDROID, OriginePaquet.de("com.google.android.katniss", Fabricant.PHILIPS))
        assertEquals(OriginePaquet.ANDROID, OriginePaquet.de("com.android.tv.settings", Fabricant.TCL))
        assertEquals(OriginePaquet.ANDROID, OriginePaquet.de("android", null))

        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("org.droidtv.playtv", Fabricant.PHILIPS))
        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("com.tcl.tv.tclhome_passive", Fabricant.TCL))
        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("com.sony.dtv.tvx", Fabricant.SONY))
        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("com.nvidia.ota", Fabricant.NVIDIA))
        // Un fondeur de puces est du côté du constructeur, quelle que soit la marque.
        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("com.mediatek.wwtv.tvcenter", Fabricant.PHILIPS))
        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("com.droidlogic.tvinput", null))

        assertEquals(OriginePaquet.AUTRE, OriginePaquet.de("com.netflix.ninja", Fabricant.TCL))
        // Le paquet d'une autre marque n'est pas celui du constructeur de cet appareil.
        assertEquals(OriginePaquet.AUTRE, OriginePaquet.de("org.droidtv.playtv", Fabricant.TCL))
        assertEquals(OriginePaquet.AUTRE, OriginePaquet.de("com.amazon.amazonvideo.livingroom", Fabricant.TCL))
        assertEquals(OriginePaquet.CONSTRUCTEUR, OriginePaquet.de("com.amazon.tv.launcher", Fabricant.AMAZON))
    }

    @Test
    fun `l'origine d'une entree du catalogue suit la marque qu'il lui donne`() {
        fun entree(marque: String, paquet: String = "a.b.c") =
            EntreePaquet(paquet = paquet, nom = "", description = "", categorie = "", marque = marque)

        assertEquals(OriginePaquet.ANDROID, entree("Google").origine)
        assertEquals(OriginePaquet.ANDROID, entree("AOSP").origine)
        assertEquals(OriginePaquet.CONSTRUCTEUR, entree("TCL").origine)
        assertEquals(OriginePaquet.CONSTRUCTEUR, entree("MediaTek").origine)
        assertEquals(OriginePaquet.AUTRE, entree("Third party").origine)
        assertEquals(OriginePaquet.ANDROID, entree("", paquet = "com.android.vending").origine)
    }

    @Test
    fun `seul ce que le catalogue ignore est inconnu, range du constructeur a l'inconnu`() {
        val catalogue = Catalogue(
            entrees = listOf(EntreePaquet("com.tcl.pub", "Pub", "", "test")),
            proteges = listOf(PaquetProtege("com.tcl.tv", "Tuner.")),
            launchers = listOf(
                LauncherRecommande("net.jolabs40.startlight", "Startlight", "", variantes = listOf("net.jolabs40.startlight.debug")),
            ),
            launchersConnus = listOf(LauncherConnu("projectivy", "Projectivy", listOf("com.spocky.projengmenu"))),
        )
        val systeme = mapOf(
            "com.tcl.pub" to EtatPaquet.DESACTIVE,
            "com.tcl.tv" to EtatPaquet.ACTIF,
            "net.jolabs40.startlight.debug" to EtatPaquet.ACTIF,
            "com.spocky.projengmenu" to EtatPaquet.ACTIF,
            "com.netflix.ninja" to EtatPaquet.ACTIF,
            "com.google.android.katniss" to EtatPaquet.ACTIF,
            "com.tcl.tv.tclhome_passive" to EtatPaquet.DESACTIVE,
            "com.mediatek.wwtv.tvcenter" to EtatPaquet.ACTIF,
        )

        val inconnus = catalogue.paquetsInconnus(systeme, Fabricant.TCL)

        assertEquals(
            listOf(
                "com.mediatek.wwtv.tvcenter",
                "com.tcl.tv.tclhome_passive",
                "com.google.android.katniss",
                "com.netflix.ninja",
            ),
            inconnus.map { it.paquet },
        )
        assertEquals(EtatPaquet.DESACTIVE, inconnus.first { it.paquet == "com.tcl.tv.tclhome_passive" }.etat)
        assertEquals("com.mediatek", inconnus.first().famille)

        // Le cadre et ses surcouches forment une seule famille, pas une par paquet.
        fun famille(paquet: String) = PaquetInconnu(paquet, EtatPaquet.ACTIF, OriginePaquet.ANDROID).famille
        assertEquals("android", famille("android"))
        assertEquals("android", famille("android.auto_generated_rro_vendor__"))
        assertEquals("android", famille("android.autoinstalls.config.google.gtvpai"))
        assertEquals("org.droidtv", famille("org.droidtv.playtv"))
    }

    @Test
    fun `l'inventaire dit l'appareil et range chaque paquet par origine et par famille`() {
        val philips = InfosAppareil(
            marque = "TPV",
            marqueCommerciale = "Philips",
            modele = "55PUS8807/12",
            versionAndroid = "11",
            build = "TPM211E",
        )
        val inconnus = listOf(
            PaquetInconnu("org.droidtv.playtv", EtatPaquet.ACTIF, OriginePaquet.CONSTRUCTEUR),
            PaquetInconnu("org.droidtv.welcome", EtatPaquet.DESACTIVE, OriginePaquet.CONSTRUCTEUR),
            PaquetInconnu("com.google.android.katniss", EtatPaquet.ACTIF, OriginePaquet.ANDROID),
        )

        val mo = 1024L * 1024
        val rapport = RapportInconnus.markdown(
            infos = philips,
            inconnus = inconnus,
            application = "TV Slim pour Windows 1.2.0",
            releve = ReleveInconnus(
                indices = mapOf(
                    "org.droidtv.playtv" to IndicesPaquet(
                        chemin = "/system/priv-app/PlayTv/PlayTv.apk",
                        uid = 1000,
                        declarations = setOf(DeclarationSensible.DEMARRAGE, DeclarationSensible.ENTREE_TV),
                        icone = true,
                    ),
                    "com.google.android.katniss" to IndicesPaquet(
                        chemin = "/product/priv-app/Katniss/Katniss.apk",
                        misAJour = true,
                        uid = 10036,
                    ),
                ),
                memoire = RepartitionMemoire(
                    totalKo = 2_000_000,
                    processus = listOf(
                        ProcessusMemoire("org.droidtv.playtv", pid = 1200, kilooctets = 40_960),
                        ProcessusMemoire("org.droidtv.playtv:tuner", pid = 1201, kilooctets = 10_240),
                    ),
                ),
                stockage = RepartitionStockage(
                    applications = listOf(StockageApplication("org.droidtv.playtv", 50 * mo, 2 * mo, 0)),
                ),
            ),
        )

        assertTrue(rapport, rapport.contains("- Appareil : Philips 55PUS8807/12"))
        assertTrue(rapport, rapport.contains("- Android : 11 (TPM211E)"))
        assertTrue(rapport, rapport.contains("constructeur 2, Android 1, autres 0"))
        assertTrue(rapport, rapport.contains("- Avec les droits du système : 1 ; avec une déclaration sensible : 1"))
        assertTrue(rapport, rapport.contains("- Lu sur l'appareil : indices ADB, mémoire vive, stockage\n"))
        assertTrue(rapport, rapport.indexOf("## Constructeur") < rapport.indexOf("## Android"))
        assertTrue(rapport, rapport.contains("### org.droidtv (2)"))
        // La mémoire réunit les processus du paquet ; les déclarations vont de la plus grave à la moins grave.
        assertTrue(
            rapport,
            rapport.contains("| `org.droidtv.playtv` | actif | system/priv-app | système | entrée TV, démarrage | oui | 50 Mo | 52 Mo |"),
        )
        assertTrue(rapport, rapport.contains("| `org.droidtv.welcome` | désactivé | — | — | — | — | — | — |"))
        assertTrue(
            rapport,
            rapport.contains("| `com.google.android.katniss` | actif | product/priv-app, mise à jour | appli | — | non | — | — |"),
        )

        // Rien de relu : l'inventaire se fait quand même, et dit ce qui manque.
        val nu = RapportInconnus.markdown(philips, inconnus, "TV Slim Remote 1.2.0")
        assertTrue(nu, nu.contains("- Lu sur l'appareil : la seule liste des paquets ; illisible : indices ADB, mémoire vive, stockage"))
        assertTrue(nu, !nu.contains("droits du système :"))
        assertEquals("TVSlim-inconnus-Philips-55PUS8807-12-2026-09-13.md",
            RapportInconnus.nomPropose(philips, java.time.LocalDate.of(2026, 9, 13)))
    }
}
