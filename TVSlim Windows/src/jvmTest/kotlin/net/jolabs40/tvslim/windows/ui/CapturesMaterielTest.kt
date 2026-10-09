package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.configuration.FichierConfiguration
import net.jolabs40.tvslim.configuration.configurationDe
import net.jolabs40.tvslim.configuration.planifier
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.fichiers.LectureDossier
import net.jolabs40.tvslim.ecran.EnregistrementTv
import net.jolabs40.tvslim.windows.Emplacements
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.DepotCles
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.data.PreferencesWindows
import net.jolabs40.tvslim.windows.ecran.InstallationScrcpy
import net.jolabs40.tvslim.windows.ecran.LocalisationScrcpy
import net.jolabs40.tvslim.windows.maj.ClientGithub
import net.jolabs40.tvslim.windows.maj.Distribution
import net.jolabs40.tvslim.windows.maj.InstallateurMiseAJour
import net.jolabs40.tvslim.windows.maj.ModeDistribution
import net.jolabs40.tvslim.windows.maj.PiloteMisesAJour
import net.jolabs40.tvslim.windows.maj.Version
import net.jolabs40.tvslim.windows.reseau.DecouverteTv
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.msg_unknown_export_failed
import net.jolabs40.tvslim.windows.ressources.msg_unknown_exported
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import javax.swing.SwingUtilities

/**
 * Renders the window off screen with data from a real TV: real ADB connection, snapshot, memory, network
 * discovery. Opt-in:
 *
 *     ./gradlew jvmTest --tests "*CapturesMaterielTest*" -Pmateriel=192.168.2.135 --rerun
 *
 * Uses the app's ADB key (`%APPDATA%\TVSlim\cles`), so an authorization accepted on the TV during the test also
 * holds for the app. Journal, measurements and preferences live in a temporary folder. No write command is sent.
 */
class CapturesMaterielTest {

    private val hote: String? = System.getProperty("tvslim.materiel")
    private val sortie = File(System.getProperty("tvslim.captures") ?: "build/captures")

    private val proprietaire = object : LifecycleOwner {
        val registre = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registre
    }

    /**
     * Runs [bloc] on the AWT thread (`Dispatchers.Main`). The registry above has no lock: composition fed it from
     * the test thread while `collectAsStateWithLifecycle` touched it from AWT, which eventually threw an
     * `ArrayIndexOutOfBoundsException`.
     */
    private fun <T> surFilAwt(bloc: () -> T): T {
        var resultat: Result<T>? = null
        SwingUtilities.invokeAndWait { resultat = runCatching(bloc) }
        return resultat!!.getOrThrow()
    }

    @Test
    fun `the four tabs with a real TV`() {
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
        val ecran = PiloteEcran(
            lecteur = client,
            enregistrement = EnregistrementTv(client, client),
            localisation = LocalisationScrcpy(emplacements.scrcpy),
            installation = InstallationScrcpy(github, emplacements.scrcpy),
            cible = { null },
            dossierImages = { temporaire },
            dossierVideos = { temporaire },
        )

        fun capturer(nom: String, onglet: Onglet, sombre: Boolean = false, attenteMs: Long = 1_500, prete: () -> Boolean = { true }) {
            // Create, render and close on the AWT thread; waits stay on the test thread so AWT can run the
            // controller's coroutines meanwhile.
            val scene = surFilAwt {
                ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
                    CompositionLocalProvider(LocalLifecycleOwner provides proprietaire) {
                        TvSlimTheme(sombre = sombre) {
                            AppFenetre(
                                pilote = pilote,
                                misesAJour = misesAJour,
                                ecran = ecran,
                                dossierScrcpy = emplacements.scrcpy,
                                onglet = onglet,
                                onOnglet = {},
                                ouvrirLien = {},
                                ouvrirDossierDonnees = {},
                                choisirFichierExport = { _, _ -> null },
                                choisirFichierImport = { null },
                                choisirApk = { null },
                                choisirFichiers = { emptyList() },
                                choisirDossier = { null },
                                choisirDestinationFichier = { _, _ -> null },
                                choisirDestinationDossier = { null },
                                ouvrirDossier = {},
                            )
                        }
                    }
                }
            }
            try {
                surFilAwt { scene.render(0) }
                val limite = System.currentTimeMillis() + attenteMs
                while (System.currentTimeMillis() < limite && !prete()) Thread.sleep(200)
                repeat(3) { i ->
                    Thread.sleep(250)
                    surFilAwt { scene.render((i + 1) * 1_000_000_000L) }
                }
                val image = surFilAwt { scene.render(5_000_000_000L) }
                File(sortie, "$nom.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally {
                surFilAwt { scene.close() }
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

        // 1. Before connecting: network discovery, for one full scan round.
        capturer("01-recherche", Onglet.TELEVISEUR, attenteMs = 15_000) {
            pilote.etat.value.decouverte.premierTourTermine
        }

        // 2. Connect. The first time, the TV asks for authorization, to be accepted with the remote;
        //    keeps retrying until the timeout.
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

        // Memory: the tab starts reading on first open. Record what happens, to tell a slow read from a
        // failed or never-started one.
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

        // Files tab: internal storage, read as on a first visit, and the mounted volumes.
        pilote.fichiers.explorateur.demarrer()
        capturer("09-fichiers", Onglet.FICHIERS, attenteMs = 20_000) {
            pilote.fichiers.explorateur.etat.value.lecture is LectureDossier.Lue
        }
        releves += "fichiers: ${pilote.fichiers.explorateur.etat.value.lecture?.javaClass?.simpleName}, " +
            "entrees=${pilote.fichiers.explorateur.etat.value.entrees.size}, " +
            "raccourcis=${pilote.fichiers.explorateur.etat.value.raccourcis.map { it.chemin }}"

        // Applications tab: the helper is copied to /data/local/tmp, run, then deleted; names and icons are read.
        // The test's cache is empty, so everything is read (about 30 s on a phone).
        pilote.applications.charger()
        capturer("10-applications", Onglet.APPLICATIONS, attenteMs = 120_000) { pilote.applications.etat.value.lue }
        releves += "applications: ${pilote.applications.etat.value.applications.size}, " +
            "avec icone=${pilote.applications.etat.value.applications.count { it.lue }}"

        // A saved configuration, read back against the TV it describes, must have nothing to reapply.
        // Computed in memory; no command is sent to the TV.
        val lu = pilote.etat.value
        val etatsLus = lu.lignes.associate { it.entree.paquet to it.etat }
        val sauvegarde = lu.catalogue.configurationDe(lu.infos, etatsLus)
        val plan = FichierConfiguration.lire(FichierConfiguration.ecrire(sauvegarde))
            ?.planifier(lu.catalogue, etatsLus, lu.infos)
        releves += "configuration: desactives=${sauvegarde.desactives.size}, actifs=${sauvegarde.actifs.size}, " +
            "accueil=${sauvegarde.accueil?.paquet}, actions=${plan?.nombreActions}, accueilAChanger=${plan?.accueil}"
        releves += "accueilsUsine=${lu.infos.accueilsUsine}"
        assertTrue("Une configuration relue doit correspondre au téléviseur : $plan", plan != null && plan.rienAFaire)
        assertTrue("L'accueil en place ne doit pas être à rétablir : ${plan?.accueil}", plan?.accueil == null)

        // Storage, read the way the tab does.
        pilote.rafraichirStockage()
        val stockageLu = attendre(40_000) { pilote.etat.value.lectureStockageTentee }
        val stockage = pilote.etat.value.stockage
        releves += "stockage: lu=$stockageLu, totalKo=${stockage.totalKo}, libreKo=${stockage.libreKo}, " +
            "applications=${stockage.applications.size}"
        assertTrue("Le stockage du téléviseur doit se lire", stockage.renseignee)

        // Unknown-package inventory, read and written as the export button does, next to the screenshots. No
        // window is open at this point, so the completion message stays and reports the outcome.
        val inventaire = File(sortie, "inconnus.md").apply { delete() }
        pilote.configuration.exporterInconnus(inventaire)
        val fins = setOf(Res.string.msg_unknown_exported, Res.string.msg_unknown_export_failed)
        val termine = attendre(60_000) { (pilote.etat.value.message as? MessageUi.Texte)?.ressource in fins }
        val rapport = inventaire.takeIf { it.isFile }?.readText().orEmpty()
        releves += "inconnus: ${lu.inconnus.size}, termine=$termine, message=${pilote.etat.value.message}"
        releves += rapport.lineSequence()
            .filter { it.startsWith("- Lu sur") || it.startsWith("- Avec les droits") || it.startsWith("- Firmware") || it.startsWith("- Déjà au") }
            .joinToString(" / ")
        assertTrue("L'inventaire doit s'écrire : ${pilote.etat.value.message}", rapport.isNotEmpty())
        assertTrue(
            "Les quatre lectures doivent aboutir : ${rapport.take(800)}",
            rapport.contains("- Lu sur l'appareil : indices ADB, mémoire vive, stockage, firmware\n"),
        )
        assertTrue("Le catalogue connaît des paquets de la TCL : ${rapport.take(800)}", rapport.contains("## Déjà au catalogue ("))

        // Brings the unknown-packages section to the top by searching for the first family name.
        lu.inconnus.firstOrNull()?.let { premier ->
            pilote.majRecherche(premier.famille)
            capturer("08-paquets-inconnus", Onglet.PAQUETS)
            pilote.majRecherche("")
        }

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
