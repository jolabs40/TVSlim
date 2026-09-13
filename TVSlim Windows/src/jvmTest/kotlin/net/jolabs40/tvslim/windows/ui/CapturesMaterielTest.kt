package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.DepotCles
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.data.PreferencesWindows
import net.jolabs40.tvslim.windows.maj.ClientGithub
import net.jolabs40.tvslim.windows.maj.Distribution
import net.jolabs40.tvslim.windows.maj.InstallateurMiseAJour
import net.jolabs40.tvslim.windows.maj.ModeDistribution
import net.jolabs40.tvslim.windows.maj.PiloteMisesAJour
import net.jolabs40.tvslim.windows.maj.Version
import net.jolabs40.tvslim.windows.reseau.DecouverteTv
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * La fenêtre, rendue hors écran avec les données d'un **vrai** téléviseur : connexion ADB réelle,
 * photographie, mémoire, découverte sur le réseau. Ne tourne que sur demande :
 *
 *     ./gradlew jvmTest --tests "*CapturesMaterielTest*" -Pmateriel=192.168.2.135 --rerun
 *
 * La clé ADB est celle de l'application (`%APPDATA%\TVSlim\cles`) : l'autorisation acceptée sur le
 * téléviseur pendant ce test vaut ensuite pour l'application. Journal, mesures et préférences, eux,
 * vivent dans un dossier temporaire. Aucune commande d'écriture n'est envoyée au téléviseur.
 */
class CapturesMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")
    private val sortie = File(System.getProperty("tvslim.captures") ?: "build/captures")

    private val proprietaire = object : LifecycleOwner {
        val registre = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registre
    }

    @Test
    fun `les quatre onglets avec un vrai televiseur`() {
        assumeTrue("-Pmateriel=<adresse> pour capturer sur un vrai téléviseur", hote != null)
        val adresse = hote!!
        sortie.mkdirs()
        Locale.setDefault(Locale.FRANCE)

        val temporaire = Files.createTempDirectory("tvslim-captures").toFile()
        val emplacements = Emplacements(File(temporaire, "donnees"), File(temporaire, "local"))
        val client = ClientAdb(DepotCles(Emplacements.windows().cles))
        val preferences = PreferencesWindows(emplacements.preferences)
        val github = ClientGithub(depot = "jolabs40/TVSlim", versionApp = "captures")
        val pilote = PiloteApp(client, CatalogueRepository(), preferences, DecouverteTv(), emplacements)
        val misesAJour = PiloteMisesAJour(
            preferences = preferences,
            client = github,
            installateur = InstallateurMiseAJour(emplacements.telechargements, github, ""),
            distribution = Distribution(ModeDistribution.DEVELOPPEMENT, null),
            versionActuelle = Version(1, 0, 0),
            depot = "jolabs40/TVSlim",
            quitter = {},
            ouvrirLien = {},
        )

        fun capturer(nom: String, onglet: Onglet, sombre: Boolean = false, attenteMs: Long = 1_500, prete: () -> Boolean = { true }) {
            val scene = ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
                CompositionLocalProvider(LocalLifecycleOwner provides proprietaire) {
                    TvSlimTheme(sombre = sombre) {
                        AppFenetre(
                            pilote = pilote,
                            misesAJour = misesAJour,
                            onglet = onglet,
                            onOnglet = {},
                            ouvrirLien = {},
                            ouvrirDossierDonnees = {},
                            choisirFichierExport = { _, _ -> null },
                        )
                    }
                }
            }
            try {
                scene.render(0)
                val limite = System.currentTimeMillis() + attenteMs
                while (System.currentTimeMillis() < limite && !prete()) Thread.sleep(200)
                repeat(3) { i ->
                    Thread.sleep(250)
                    scene.render((i + 1) * 1_000_000_000L)
                }
                val image = scene.render(5_000_000_000L)
                File(sortie, "$nom.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally {
                scene.close()
            }
        }

        fun attendre(delaiMs: Long, condition: () -> Boolean): Boolean {
            val limite = System.currentTimeMillis() + delaiMs
            while (System.currentTimeMillis() < limite) {
                if (condition()) return true
                Thread.sleep(250)
            }
            return condition()
        }

        // 1. Avant connexion : la recherche sur le réseau, le temps d'un tour de balayage.
        capturer("01-recherche", Onglet.TELEVISEUR, attenteMs = 15_000) {
            pilote.etat.value.decouverte.premierTourTermine
        }

        // 2. Connexion. La première fois, le téléviseur demande l'autorisation : on la laisse
        //    accepter à la télécommande, en retentant tant que le délai n'est pas écoulé.
        pilote.majHote(adresse)
        pilote.majPort("5555")
        val connecte = attendre(240_000) {
            val etat = pilote.etat.value
            if (etat.connexion.etat == EtatConnexion.ERREUR || etat.connexion.etat == EtatConnexion.DECONNECTE) {
                pilote.connecter()
                Thread.sleep(1_000)
            }
            etat.connecte && etat.lignes.isNotEmpty()
        }
        assertTrue("Connexion impossible : ${pilote.etat.value.connexion}", connecte)

        capturer("02-televiseur", Onglet.TELEVISEUR)

        val premierDesactive = pilote.etat.value.lignes.firstOrNull { it.etat == EtatPaquet.DESACTIVE }
            ?: pilote.etat.value.lignes.first { it.etat != EtatPaquet.ABSENT }
        pilote.detailler(premierDesactive.entree.paquet)
        capturer("03-paquets", Onglet.PAQUETS)
        capturer("04-paquets-sombre", Onglet.PAQUETS, sombre = true)

        // La mémoire : l'onglet lance lui-même la lecture à sa première ouverture. On relève ce
        // qui se passe, pour distinguer une lecture lente, ratée, ou jamais lancée.
        val releves = mutableListOf<String>()
        val debut = System.currentTimeMillis()
        var chargementVu = false
        capturer("05-memoire", Onglet.MEMOIRE, attenteMs = 40_000) {
            val etat = pilote.etat.value
            chargementVu = chargementVu || etat.chargement
            etat.lectureMemoireTentee
        }
        val apres = pilote.etat.value
        releves += "memoire: ${System.currentTimeMillis() - debut} ms, chargementVu=$chargementVu, " +
            "tentee=${apres.lectureMemoireTentee}, totalKo=${apres.memoire.totalKo}, " +
            "processus=${apres.memoire.processus.size}, connecte=${apres.connecte}"
        if (!apres.lectureMemoireTentee) {
            pilote.rafraichirMemoire()
            val lue = attendre(40_000) { pilote.etat.value.lectureMemoireTentee }
            releves += "lecture explicite: aboutie=$lue, totalKo=${pilote.etat.value.memoire.totalKo}"
            capturer("05b-memoire-explicite", Onglet.MEMOIRE)
        }
        capturer("06-journal", Onglet.JOURNAL)

        Locale.setDefault(Locale.ENGLISH)
        capturer("07-paquets-anglais", Onglet.PAQUETS)

        pilote.deconnecter()
        File(sortie, "resume.txt").writeText(
            buildString {
                val etat = pilote.etat.value
                appendLine("hote=$adresse")
                appendLine("detectes=${etat.detectes.joinToString { "${it.libelle}@${it.hote}:${it.port}" }}")
                releves.forEach { appendLine(it) }
            },
        )
    }
}
