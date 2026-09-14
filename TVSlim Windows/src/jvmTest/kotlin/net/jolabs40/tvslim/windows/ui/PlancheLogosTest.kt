package net.jolabs40.tvslim.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.commande.EchangeCommande
import net.jolabs40.tvslim.configuration.AppareilSauvegarde
import net.jolabs40.tvslim.configuration.ChangementAccueil
import net.jolabs40.tvslim.configuration.ConfigurationTv
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.device.AccueilUsine
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.Fabricant
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LauncherInstalle
import net.jolabs40.tvslim.device.OriginePaquet
import net.jolabs40.tvslim.device.RepartitionStockage
import net.jolabs40.tvslim.device.StockageApplication
import net.jolabs40.tvslim.device.origine
import net.jolabs40.tvslim.device.paquetsInconnus
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.installation.CauseEchec
import net.jolabs40.tvslim.installation.ManifesteApk
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.installation.VersionInstallee
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.windows.adb.ConnexionUi
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.reseau.AppareilDecouvert
import net.jolabs40.tvslim.windows.reseau.ResultatDecouverte
import net.jolabs40.tvslim.windows.ui.composants.LOGOS_LAUNCHERS
import net.jolabs40.tvslim.windows.ui.composants.LogoLauncher
import net.jolabs40.tvslim.windows.ui.composants.PlaqueMarque
import net.jolabs40.tvslim.windows.ui.ecrans.CarteAccueil
import net.jolabs40.tvslim.windows.ui.ecrans.CarteAppareil
import net.jolabs40.tvslim.windows.ui.ecrans.CarteCommande
import net.jolabs40.tvslim.windows.ui.ecrans.CarteInstallation
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ConnexionEcran
import net.jolabs40.tvslim.windows.ui.ecrans.MemoireEcran
import net.jolabs40.tvslim.windows.ui.ecrans.PaquetsEcran
import net.jolabs40.tvslim.windows.ui.ecrans.VoileDepot
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Les logos et les cartes qui les portent, rendus hors écran avec des données fabriquées : aucun
 * téléviseur n'est nécessaire, et tous les cas se voient d'un coup — Startlight absent, installé,
 * aucun launcher tiers ; une Philips qui se déclare « TPV » ; une box. Ne tourne que sur demande :
 *
 *     ./gradlew jvmTest --tests "*PlancheLogosTest*" -Pplanche=1 --rerun
 */
class PlancheLogosTest {

    private val sortie = File(System.getProperty("tvslim.captures") ?: "build/captures")

    private val proprietaire = object : LifecycleOwner {
        val registre = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registre
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun `planche des logos et des cartes qui les portent`() {
        assumeTrue("-Pplanche=1 pour produire la planche", System.getProperty("tvslim.planche") != null)
        sortie.mkdirs()
        Locale.setDefault(Locale.FRANCE)
        val catalogue = runBlocking { CatalogueRepository { "fr" }.catalogue() }

        fun etat(accueil: String, vararg installes: String) = EtatApp(
            catalogue = catalogue,
            infos = InfosAppareil(
                marque = "TCL",
                modele = "Smart TV Pro",
                accueilActuel = accueil,
                launchersTiers = installes.map { LauncherInstalle(paquet = it, nom = it, composant = "$it/.Accueil") },
            ),
        )

        rendre("10-accueil-recommandation", 720, 980) {
            CarteAccueil(etat("com.spocky.projengmenu", "com.spocky.projengmenu", "com.exemple.launcher.inconnu")) {}
        }
        rendre("11-accueil-startlight-installe", 720, 520) {
            CarteAccueil(etat("net.jolabs40.startlight.debug", "net.jolabs40.startlight.debug", "me.efesser.flauncher")) {}
        }
        rendre("12-accueil-aucun-sombre", 720, 900, sombre = true) {
            CarteAccueil(etat("com.google.android.apps.tv.launcherx")) {}
        }
        rendre("13-logos-launchers", 960, 330) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LOGOS_LAUNCHERS.keys.forEach { id ->
                    Column(modifier = Modifier.width(120.dp)) {
                        LogoLauncher(id = id, taille = 64.dp)
                        Text(
                            text = catalogue.launchersConnus.firstOrNull { it.id == id }?.nom
                                ?: catalogue.launchers.firstOrNull { it.id == id }?.nom
                                ?: id,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        rendre("15-marques", 1080, 300) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Fabricant.entries.forEach { PlaqueMarque(fabricant = it, hauteur = 44.dp) }
            }
        }
        rendre("16-marques-sombre", 1080, 240, sombre = true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Fabricant.entries.forEach { PlaqueMarque(fabricant = it, hauteur = 30.dp) }
            }
        }

        // Une Philips se déclare « TPV » : la fiche doit dire Philips. Une Shield est une box.
        rendre("17-appareil-philips", 720, 440) {
            CarteAppareil(
                EtatApp(
                    infos = InfosAppareil(
                        marque = "TPV", marqueCommerciale = "Philips", modele = "55PUS8807/12", versionAndroid = "11",
                        memoireTotaleMo = 2800, memoireLibreMo = 900, paquetsInstalles = 180, paquetsDesactives = 12,
                        accueilActuel = "com.google.android.apps.tv.launcherx",
                    ),
                ),
                {}, {},
            )
        }
        rendre("18-appareil-shield-sombre", 720, 440, sombre = true) {
            CarteAppareil(
                EtatApp(
                    infos = InfosAppareil(
                        marque = "NVIDIA", marqueCommerciale = "NVIDIA", modele = "SHIELD Android TV", versionAndroid = "11",
                        memoireTotaleMo = 2950, memoireLibreMo = 1400, paquetsInstalles = 150, paquetsDesactives = 14,
                        accueilActuel = "com.spocky.projengmenu",
                    ),
                ),
                {}, {},
            )
        }

        // Les téléviseurs trouvés : ceux déjà joints une fois portent leur marque.
        val decouverte = EtatApp(
            catalogue = catalogue,
            decouverte = ResultatDecouverte(
                appareils = listOf(
                    AppareilDecouvert(nom = "tcl", hote = "192.168.2.135", port = 5555),
                    AppareilDecouvert(nom = "shieldtv", hote = "192.168.2.193", port = 5555),
                    AppareilDecouvert(nom = "192.168.2.40", hote = "192.168.2.40", port = 5555),
                ),
                premierTourTermine = true,
            ),
            nomsConnus = mapOf("192.168.2.135" to "TCL Smart TV Pro", "192.168.2.193" to "NVIDIA SHIELD Android TV"),
        )
        rendre("19-decouverte-marques", 1280, 640, cadre = false) {
            ConnexionEcran(
                decouverte, {}, {}, {}, {}, {}, {}, {}, {}, {}, ActionsPermissions({}, {}, {}, {}, {}), {},
                ActionsCommande({}, {}, {}),
            )
        }

        // L'accueil d'usine coupé reste listé, et l'accueil en place se lit en grand.
        val launcherx = "com.google.android.apps.tv.launcherx"
        val usineCoupee = EtatApp(
            catalogue = catalogue,
            infos = InfosAppareil(
                marque = "TCL",
                modele = "Smart TV Pro",
                accueilActuel = "net.jolabs40.startlight.debug",
                launchersTiers = listOf("com.spocky.projengmenu", "net.jolabs40.startlight.debug")
                    .map { LauncherInstalle(paquet = it, nom = it, composant = "$it/.Accueil") },
                accueilsUsine = listOf(AccueilUsine(launcherx, "$launcherx/.home.HomeActivity", actif = false)),
            ),
        )
        rendre("20-accueil-usine-desactive", 720, 560) { CarteAccueil(usineCoupee) {} }

        val usineSeule = EtatApp(
            catalogue = catalogue,
            infos = InfosAppareil(
                marque = "TCL",
                modele = "Smart TV Pro",
                accueilActuel = launcherx,
                accueilsUsine = listOf(AccueilUsine(launcherx, "$launcherx/.home.HomeActivity", actif = true)),
            ),
        )
        rendre("21-accueil-usine-seul", 720, 900) { CarteAccueil(usineSeule) {} }

        // Ce qu'une configuration réinjectée changerait, avant d'y toucher.
        val plan = PlanReinjection(
            configuration = ConfigurationTv(
                application = ConfigurationTv.APPLICATION,
                format = ConfigurationTv.FORMAT,
                sauvegardeLe = 1_789_300_000_000,
                appareil = AppareilSauvegarde(nom = "TCL Smart TV Pro", versionAndroid = "14"),
            ),
            aReactiver = catalogue.entrees.filter { it.categorie == "streaming" }.take(1),
            aDesactiver = catalogue.entrees.filter { it.marque == "TCL" }.take(4),
            ignores = listOf("com.nvidia.stats", "com.nvidia.feedback"),
            accueil = ChangementAccueil("com.spocky.projengmenu", "Projectivy Launcher", composant = ""),
        )
        rendre("22-reinjection", 900, 760, cadre = false) {
            ConfirmationDialogue(Confirmation.Reinjection(plan), {}, {})
        }

        // L'onglet Mémoire, basculé sur le stockage d'un clic sur le second segment.
        val stockage = EtatApp(
            catalogue = catalogue,
            connexion = ConnexionUi(etat = EtatConnexion.CONNECTE, hote = "192.168.2.135"),
            lectureStockageTentee = true,
            stockage = RepartitionStockage(
                totalKo = 51_170_024,
                libreKo = 45_111_156,
                applicationsOctets = 2_664_341_504,
                donneesOctets = 1_880_899_072,
                cacheOctets = 961_830_912,
                photosOctets = 129_970_176,
                autresOctets = 764_686_336,
                applications = listOf(
                    StockageApplication("com.netflix.ninja", 152_000_000, 71_000_000, 43_000_000),
                    StockageApplication("com.google.android.apps.mediashell", 114_307_072, 376_832, 24_576),
                    StockageApplication("com.spocky.projengmenu", 48_000_000, 12_000_000, 3_000_000),
                    StockageApplication("com.tcl.esticker", 53_248, 221_184, 16_384),
                ),
            ),
        )
        // Le sélecteur fait 360 de large à partir de 20 : le second segment couvre 200 à 380.
        rendre("23-stockage", 1280, 720, cadre = false, clic = Offset(290f, 36f)) {
            MemoireEcran(stockage, {}, {}, {}, {})
        }

        // L'onglet Paquets, liste des profils ouverte d'un clic : le champ est en haut à droite.
        val lignes = catalogue.entrees.mapIndexed { rang, entree ->
            LignePaquet(entree, if (rang % 5 == 0) EtatPaquet.DESACTIVE else EtatPaquet.ACTIF)
        }
        val paquets = EtatApp(
            catalogue = catalogue,
            connexion = ConnexionUi(etat = EtatConnexion.CONNECTE, hote = "192.168.2.135"),
            lignes = lignes,
        )
        rendre("14-paquets-profils-ouverts", 1280, 860, cadre = false, clic = Offset(1080f, 110f)) {
            PaquetsEcran(paquets, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

        // Une ligne du catalogue par origine, puis ce qu'il ignore, rangé par éditeur.
        val inconnus = EtatApp(
            catalogue = catalogue,
            connexion = ConnexionUi(etat = EtatConnexion.CONNECTE, hote = "192.168.2.135"),
            lignes = OriginePaquet.ORDRE.mapNotNull { origine -> lignes.firstOrNull { it.entree.origine == origine } },
            inconnus = catalogue.paquetsInconnus(
                systeme = mapOf(
                    "com.tcl.guard" to EtatPaquet.ACTIF,
                    "com.tcl.tvinput" to EtatPaquet.ACTIF,
                    "com.tcl.inputmethod.international" to EtatPaquet.ACTIF,
                    "com.mediatek.android.tv.mdns.offload.overlay" to EtatPaquet.ACTIF,
                    "com.mediatek.AirplayAPK" to EtatPaquet.DESACTIVE,
                    "com.google.android.tv.remote.service" to EtatPaquet.ACTIF,
                    "com.android.se" to EtatPaquet.ACTIF,
                    "com.dolby.android.audio.service" to EtatPaquet.ACTIF,
                ),
                fabricant = Fabricant.TCL,
            ),
        )
        rendre("24-paquets-inconnus", 1280, 860, cadre = false) {
            PaquetsEcran(inconnus, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

        // L'installation d'un APK : l'onglet Téléviseur joint, un envoi en cours, les deux bilans, la
        // confirmation d'un retour en arrière et le voile d'un fichier qu'on glisse dans la fenêtre.
        val hippie = ApkChoisi(
            fichier = File("HippieTV-2.4.0.apk"),
            nom = "HippieTV-2.4.0.apk",
            taille = 48_300_000,
            manifeste = ManifesteApk("net.jolabs40.hippietv", versionCode = 240, versionName = "2.4.0", minSdk = 26),
            installee = VersionInstallee(251, "2.5.1"),
        )
        val joint = EtatApp(
            catalogue = catalogue,
            connexion = ConnexionUi(etat = EtatConnexion.CONNECTE, hote = "192.168.2.135"),
            infos = InfosAppareil(marque = "TCL", modele = "Smart TV Pro", versionAndroid = "14"),
            installation = EtatInstallation(phase = PhaseInstallation.Envoi(21_700_000, 48_300_000)),
        )
        rendre("25-televiseur-installation", 1280, 1100, cadre = false) {
            ConnexionEcran(
                joint, {}, {}, {}, {}, {}, {}, {}, {}, {}, ActionsPermissions({}, {}, {}, {}, {}), {},
                ActionsCommande({}, {}, {}),
            )
        }
        rendre("26-installation-bilans", 720, 620) {
            CarteInstallation(EtatInstallation(derniere = ResultatInstallation.Reussie(hippie))) {}
            Spacer(Modifier.height(16.dp))
            CarteInstallation(
                EtatInstallation(
                    derniere = ResultatInstallation.Echouee(
                        apk = hippie,
                        cause = CauseEchec.SIGNATURE_DIFFERENTE,
                        detail = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package net.jolabs40.hippietv " +
                            "signatures do not match newer version; ignoring!]",
                    ),
                ),
            ) {}
        }
        rendre("27-confirmation-installation", 900, 520, cadre = false) {
            ConfirmationDialogue(Confirmation.Installation(hippie), {}, {})
        }
        rendre("28-depot-apk", 900, 520, cadre = false) {
            VoileDepot(connecte = true, nomTeleviseur = "TCL Smart TV Pro")
        }

        // La commande libre : une sortie ordinaire, puis une commande qui ne finit pas et que le délai coupe.
        rendre("29-commande-adb", 720, 1040) {
            CarteCommande(
                EtatCommande(
                    saisie = "pm list packages -d",
                    derniere = EchangeCommande(
                        commande = "pm list packages -d",
                        code = 0,
                        sortie = listOf("com.tcl.gallery", "com.tcl.esticker", "com.google.android.apps.tv.launcherx")
                            .joinToString("\n") { "package:$it" },
                    ),
                ),
                ActionsCommande({}, {}, {}),
            )
            Spacer(Modifier.height(16.dp))
            CarteCommande(
                EtatCommande(
                    saisie = "logcat",
                    derniere = EchangeCommande(
                        commande = "logcat",
                        code = null,
                        sortie = "09-14 08:31:02.114  1532  1532 I ActivityManager: Start proc 4121:com.tcl.tvweishi",
                        interruption = Interruption.DELAI,
                        motif = "délai dépassé",
                    ),
                ),
                ActionsCommande({}, {}, {}),
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun rendre(
        nom: String,
        largeur: Int,
        hauteur: Int,
        sombre: Boolean = false,
        cadre: Boolean = true,
        clic: Offset? = null,
        contenu: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width = largeur, height = hauteur, density = Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides proprietaire) {
                TvSlimTheme(sombre = sombre) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        if (cadre) {
                            Column(modifier = Modifier.padding(16.dp)) { contenu() }
                        } else {
                            contenu()
                        }
                    }
                }
            }
        }
        try {
            repeat(4) { i ->
                scene.render(i * 100_000_000L)
                Thread.sleep(150)
            }
            if (clic != null) {
                scene.sendPointerEvent(PointerEventType.Press, clic)
                scene.sendPointerEvent(PointerEventType.Release, clic)
                repeat(4) { i ->
                    scene.render(1_000_000_000L + i * 100_000_000L)
                    Thread.sleep(150)
                }
            }
            val image = scene.render(3_000_000_000L)
            File(sortie, "$nom.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
