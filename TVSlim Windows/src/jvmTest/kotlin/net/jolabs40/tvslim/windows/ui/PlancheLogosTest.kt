package net.jolabs40.tvslim.windows.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
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
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.Fabricant
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.LauncherInstalle
import net.jolabs40.tvslim.windows.adb.ConnexionUi
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.reseau.AppareilDecouvert
import net.jolabs40.tvslim.windows.reseau.ResultatDecouverte
import net.jolabs40.tvslim.windows.ui.composants.LOGOS_LAUNCHERS
import net.jolabs40.tvslim.windows.ui.composants.LogoLauncher
import net.jolabs40.tvslim.windows.ui.composants.PlaqueMarque
import net.jolabs40.tvslim.windows.ui.ecrans.CarteAccueil
import net.jolabs40.tvslim.windows.ui.ecrans.CarteAppareil
import net.jolabs40.tvslim.windows.ui.ecrans.ConnexionEcran
import net.jolabs40.tvslim.windows.ui.ecrans.PaquetsEcran
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
            ConnexionEcran(decouverte, {}, {}, {}, {}, {}, {}, {}, {}, {}, ActionsPermissions({}, {}, {}, {}, {}))
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
            PaquetsEcran(paquets, {}, {}, {}, {}, {}, {}, {}, {})
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
