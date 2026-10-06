package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.applicationtv.PublicationTv
import net.jolabs40.tvslim.applicationtv.SituationTv
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
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
import net.jolabs40.tvslim.fichiers.AvanceeDepot
import net.jolabs40.tvslim.fichiers.CibleLocale
import net.jolabs40.tvslim.fichiers.EchecDepot
import net.jolabs40.tvslim.fichiers.EntreeDistante
import net.jolabs40.tvslim.fichiers.EtatExplorateur
import net.jolabs40.tvslim.fichiers.FichierDistant
import net.jolabs40.tvslim.fichiers.FichierLocal
import net.jolabs40.tvslim.fichiers.LectureDossier
import net.jolabs40.tvslim.fichiers.LotLocal
import net.jolabs40.tvslim.fichiers.NatureEntree
import net.jolabs40.tvslim.fichiers.NatureSuppression
import net.jolabs40.tvslim.fichiers.PlanDepot
import net.jolabs40.tvslim.fichiers.PlanRapatriement
import net.jolabs40.tvslim.fichiers.PlanSuppression
import net.jolabs40.tvslim.fichiers.Raccourci
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.fichiers.SensTransfert
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
import net.jolabs40.tvslim.windows.maj.EtatMiseAJour
import net.jolabs40.tvslim.windows.ui.ecrans.AProposDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ActionsEcran
import net.jolabs40.tvslim.windows.ui.ecrans.ActionsFichiers
import net.jolabs40.tvslim.windows.ui.ecrans.ApercuCaptureDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.BanniereSoutien
import net.jolabs40.tvslim.windows.ui.ecrans.BoutonSoutien
import net.jolabs40.tvslim.windows.ui.ecrans.CarteAccueil
import net.jolabs40.tvslim.windows.ui.ecrans.CarteAppareil
import net.jolabs40.tvslim.windows.ui.ecrans.CarteCommande
import net.jolabs40.tvslim.windows.ui.ecrans.CarteInstallation
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationDepot
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationRapatriement
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationSuppression
import net.jolabs40.tvslim.windows.ui.ecrans.ConnexionEcran
import net.jolabs40.tvslim.windows.ui.ecrans.FermetureDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.FichiersEcran
import net.jolabs40.tvslim.windows.ui.ecrans.MemoireEcran
import net.jolabs40.tvslim.windows.ui.ecrans.PaquetsEcran
import net.jolabs40.tvslim.windows.ui.ecrans.TelechargementScrcpyDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.VideoEnregistreeDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.VoileDepot
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

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
            CarteAccueil(etat("com.spocky.projengmenu", "com.spocky.projengmenu", "com.exemple.launcher.inconnu"), {}, {}, {})
        }
        rendre("11-accueil-startlight-installe", 720, 520) {
            CarteAccueil(etat("net.jolabs40.startlight.debug", "net.jolabs40.startlight.debug", "me.efesser.flauncher"), {}, {}, {})
        }
        rendre("12-accueil-aucun-sombre", 720, 900, sombre = true) {
            CarteAccueil(etat("com.google.android.apps.tv.launcherx"), {}, {}, {})
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
                decouverte, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, ActionsPermissions({}, {}, {}, {}, {}),
                EtatApplicationTvUi(), ActionsApplicationTv({}, {}, {}), {},
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
        rendre("20-accueil-usine-desactive", 720, 560) { CarteAccueil(usineCoupee, {}, {}, {}) }

        val usineSeule = EtatApp(
            catalogue = catalogue,
            infos = InfosAppareil(
                marque = "TCL",
                modele = "Smart TV Pro",
                accueilActuel = launcherx,
                accueilsUsine = listOf(AccueilUsine(launcherx, "$launcherx/.home.HomeActivity", actif = true)),
            ),
        )
        rendre("21-accueil-usine-seul", 720, 900) { CarteAccueil(usineSeule, {}, {}, {}) }

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
                joint, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, ActionsPermissions({}, {}, {}, {}, {}),
                // La carte de l'application TV, absente du téléviseur, la 1.1.0 publiée.
                EtatApplicationTvUi(
                    situation = SituationTv(
                        disponible = PublicationTv("1.1.0", 10100, "TVSlim-TV-1.1.0.apk", "https://github.com/", 1_300_000),
                    ),
                ),
                ActionsApplicationTv({}, {}, {}), {},
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

        // L'onglet Fichiers : un dossier lu, un envoi en cours, sa confirmation, un dossier refusé.
        val jour = 1_790_000_000_000L
        val films = EtatExplorateur(
            chemin = "/sdcard/Movies",
            lecture = LectureDossier.Lue(
                "/sdcard/Movies",
                listOf(
                    EntreeDistante("Séries", NatureEntree.DOSSIER, 4096, jour),
                    EntreeDistante("Vacances 2024", NatureEntree.DOSSIER, 4096, jour - 86_400_000L),
                    EntreeDistante(".thumbnails", NatureEntree.DOSSIER, 4096, jour),
                    EntreeDistante("Le Grand Bleu (1988).mkv", NatureEntree.FICHIER, 4_381_220_112, jour),
                    EntreeDistante("bande-annonce.mp4", NatureEntree.FICHIER, 48_300_000, jour),
                    EntreeDistante("sous-titres.srt", NatureEntree.FICHIER, 91_204, jour),
                    EntreeDistante("dernier", NatureEntree.FICHIER, 17, jour, lien = true),
                ),
            ),
            raccourcis = Raccourci.avecVolumes(listOf("1A2B-3C4D")),
        )
        val actions = ActionsFichiers({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        rendre("30-fichiers", 1280, 760, cadre = false) {
            FichiersEcran(connecte = true, etat = films, actions = actions)
        }
        rendre("31-fichiers-envoi-sombre", 1280, 520, sombre = true, cadre = false) {
            FichiersEcran(
                connecte = true,
                etat = films.copy(
                    avancee = AvanceeDepot("Vacances 2024/plage.jpg", 37, 212, 1_204_000_000, 2_910_000_000),
                ),
                actions = actions,
            )
        }
        val lot = LotLocal(
            fichiers = listOf("Vacances 2024/plage.jpg", "Vacances 2024/dune.jpg", "Le Grand Bleu (1988).mkv")
                .map { chemin ->
                    object : FichierLocal {
                        override val chemin = chemin
                        override val taille = 2_000_000_000L
                        override val date = 0L
                        override fun ouvrir() = ByteArrayInputStream(ByteArray(0))
                    }
                },
            dossiers = listOf("Vacances 2024", "Vacances 2024/vide"),
        )
        rendre("32-fichiers-confirmation", 900, 520, cadre = false) {
            ConfirmationDepot(
                PlanDepot("/sdcard/Movies", lot, existants = listOf("Le Grand Bleu (1988).mkv", "Vacances 2024")),
                {},
                {},
            )
        }
        rendre("33-fichiers-refus", 1280, 560, cadre = false) {
            FichiersEcran(
                connecte = true,
                etat = EtatExplorateur(
                    chemin = "/data",
                    lecture = LectureDossier.Refusee("/data"),
                    dernier = ResultatDepot(
                        destination = "/system",
                        envoyes = 0,
                        nombre = 2,
                        echecs = listOf(
                            EchecDepot("a.txt", "couldn't create file: Read-only file system"),
                            EchecDepot("b.txt", "couldn't create file: Read-only file system"),
                        ),
                    ),
                ),
                actions = actions,
            )
        }
        rendre("34-depot-fichiers", 900, 520, cadre = false) {
            VoileDepot(connecte = true, nomTeleviseur = "TCL Smart TV Pro", destination = "/sdcard/Movies")
        }

        // Copier vers le PC et supprimer : au survol, au clic droit, leurs confirmations, la copie en cours.
        rendre("35-fichiers-survol", 1280, 520, cadre = false, survol = Offset(600f, 330f)) {
            FichiersEcran(connecte = true, etat = films, actions = actions)
        }
        rendre("36-fichiers-clic-droit", 1280, 520, cadre = false, clicDroit = Offset(600f, 344f)) {
            FichiersEcran(connecte = true, etat = films, actions = actions)
        }
        rendre("37-fichiers-copie-sombre", 1280, 520, sombre = true, cadre = false) {
            FichiersEcran(
                connecte = true,
                etat = films.copy(
                    avancee = AvanceeDepot("Vacances 2024/plage.jpg", 12, 212, 404_000_000, 2_910_000_000, SensTransfert.RECEPTION),
                ),
                actions = actions,
            )
        }
        val telechargements = object : CibleLocale {
            override fun decrire(chemin: String) =
                "C:\\Users\\Camille\\Downloads" + chemin.split('/').filter { it.isNotEmpty() }.joinToString("") { "\\$it" }
            override fun existe(chemin: String) = true
            override fun creerDossier(chemin: String) = Unit
            override fun ecrire(chemin: String) = error("rien ne s'écrit")
        }
        rendre("38-fichiers-copie-confirmation", 900, 520, cadre = false) {
            ConfirmationRapatriement(
                PlanRapatriement(
                    source = "/sdcard/Movies/Vacances 2024",
                    cible = telechargements,
                    nom = "Vacances 2024",
                    dossier = true,
                    fichiers = List(212) { FichierDistant("/sdcard/Movies/Vacances 2024/$it.jpg", "Vacances 2024/$it.jpg", 13_726_000, 0L) },
                    dossiers = listOf("Vacances 2024", "Vacances 2024/vide"),
                    existant = true,
                ),
                {},
                {},
            )
        }
        rendre("39-fichiers-suppression-confirmation", 900, 520, cadre = false) {
            ConfirmationSuppression(
                PlanSuppression("/sdcard/Movies/Vacances 2024", NatureSuppression.DOSSIER, 212, 4, 2_910_000_000),
                {},
                {},
            )
        }
        rendre("40-fichiers-derniere-copie", 1280, 600, cadre = false) {
            FichiersEcran(
                connecte = true,
                etat = films.copy(
                    dernier = ResultatDepot(
                        destination = "C:\\Users\\Camille\\Downloads\\Vacances 2024",
                        envoyes = 211,
                        nombre = 212,
                        echecs = listOf(EchecDepot("Vacances 2024/dune.jpg", "open failed: Permission denied")),
                        sens = SensTransfert.RECEPTION,
                    ),
                ),
                actions = actions,
            )
        }

        // Le bandeau de soutien, en haut de la fenêtre, et son lien permanent dans « À propos ».
        rendre("41-soutien", 1280, 80, cadre = false) { BanniereSoutien(true, {}, {}, {}) }
        rendre("42-soutien-sombre", 1280, 80, sombre = true, cadre = false) { BanniereSoutien(true, {}, {}, {}) }
        rendre("43-a-propos", 900, 760, cadre = false) {
            AProposDialogue(EtatMiseAJour(versionActuelle = "1.4.0"), {}, {}, {}, {}, {}, {}, {})
        }

        // L'écran du téléviseur : les boutons de la barre du haut au repos, puis pendant un miroir, un
        // enregistrement et un téléchargement ; l'aperçu d'une capture, la proposition de scrcpy, la vidéo.
        rendre("44-ecran-boutons", 640, 300) {
            val il = System.currentTimeMillis()
            ActionsEcran(EtatEcran(), connecte = true, {}, {}, {}, {}, {})
            ActionsEcran(EtatEcran(scrcpy = PhaseScrcpy.Actif), connecte = true, {}, {}, {}, {}, {})
            ActionsEcran(EtatEcran(enregistrement = PhaseEnregistrement.EnCours(il - 83_000, null)), connecte = true, {}, {}, {}, {}, {})
            ActionsEcran(EtatEcran(enregistrement = PhaseEnregistrement.EnCours(il - 42_000, 180)), connecte = true, {}, {}, {}, {}, {})
            ActionsEcran(
                EtatEcran(captureEnCours = true, scrcpy = PhaseScrcpy.Telechargement(0.4f), enregistrement = PhaseEnregistrement.Copie(0.7f)),
                connecte = true, {}, {}, {}, {}, {},
            )
        }
        val png = ByteArrayOutputStream().also { flux ->
            val image = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
            val pinceau = image.createGraphics()
            pinceau.paint = GradientPaint(0f, 0f, java.awt.Color(0x1F4A6E), 1920f, 1080f, java.awt.Color(0x9CF2C9))
            pinceau.fillRect(0, 0, 1920, 1080)
            pinceau.dispose()
            ImageIO.write(image, "png", flux)
        }.toByteArray()
        val dossierImages = File("C:/Users/Camille/Pictures/TV Slim")
        rendre("45-apercu-capture", 1000, 760, cadre = false) {
            ApercuCaptureDialogue(
                CaptureFaite(File(dossierImages, "TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-15-30.png"), png, 1920, 1080),
                {},
                {},
                {},
            )
        }
        rendre("46-telechargement-scrcpy", 900, 560, cadre = false) {
            TelechargementScrcpyDialogue(PhaseScrcpy.Telechargement(0.62f), File("C:/Users/Camille/AppData/Local/TVSlim/scrcpy"), {}, {})
        }
        rendre("47-video-enregistree", 900, 560, sombre = true, cadre = false) {
            VideoEnregistreeDialogue(File("C:/Users/Camille/Videos/TV Slim/TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-20-02.mp4"), {}, {})
        }
        rendre("48-fermeture-video", 900, 480, cadre = false) { FermetureDialogue(PhaseEnregistrement.Copie(0.55f)) }
        // Un téléphone joint : seul l'accueil en place, ni recommandation ni accueils d'usine (relevé du Pixel 9a).
        val pixel = EtatApp(
            catalogue = catalogue,
            infos = InfosAppareil(
                marque = "Google",
                marqueCommerciale = "google",
                modele = "Pixel 9a",
                accueilActuel = "com.teslacoilsw.launcher",
                launchersTiers = listOf("net.jolabs40.startlight.debug", "com.teslacoilsw.launcher")
                    .map { LauncherInstalle(paquet = it, nom = it, composant = "$it/.Accueil") },
                caracteristiques = "nosdcard",
                fonctions = setOf(InfosAppareil.FONCTION_TACTILE),
            ),
        )
        rendre("49-accueil-telephone", 720, 300) { CarteAccueil(pixel, {}, {}, {}) }

        // Le symbole de Ko-fi de la barre du haut, en clair et en sombre.
        rendre("50-bouton-soutien", 120, 80) { BoutonSoutien {} }
        rendre("51-bouton-soutien-sombre", 120, 80, sombre = true) { BoutonSoutien {} }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun rendre(
        nom: String,
        largeur: Int,
        hauteur: Int,
        sombre: Boolean = false,
        cadre: Boolean = true,
        clic: Offset? = null,
        survol: Offset? = null,
        clicDroit: Offset? = null,
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
            if (survol != null) {
                scene.sendPointerEvent(PointerEventType.Enter, survol)
                scene.sendPointerEvent(PointerEventType.Move, survol)
                repeat(4) { i ->
                    scene.render(1_000_000_000L + i * 100_000_000L)
                    Thread.sleep(150)
                }
            }
            if (clicDroit != null) {
                val droit = PointerButtons(isSecondaryPressed = true)
                scene.sendPointerEvent(PointerEventType.Move, clicDroit)
                scene.sendPointerEvent(PointerEventType.Press, clicDroit, buttons = droit, button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, clicDroit, button = PointerButton.Secondary)
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
