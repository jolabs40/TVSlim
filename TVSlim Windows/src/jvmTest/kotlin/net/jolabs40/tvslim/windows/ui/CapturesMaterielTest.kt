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

    /**
     * Exécute [bloc] sur le fil d'AWT, celui de `Dispatchers.Main`. Le registre ci-dessus n'a aucun
     * verrou : la composition l'alimentait depuis le fil du test pendant que `collectAsStateWithLifecycle`
     * y touchait depuis AWT, et un `ArrayIndexOutOfBoundsException` finissait par tomber. Un seul fil,
     * plus de course.
     */
    private fun <T> surFilAwt(bloc: () -> T): T {
        var resultat: Result<T>? = null
        SwingUtilities.invokeAndWait { resultat = runCatching(bloc) }
        return resultat!!.getOrThrow()
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
            // Création, rendus et fermeture sur le fil d'AWT ; les attentes, elles, restent sur celui du
            // test, pour laisser AWT dérouler les coroutines du pilote pendant ce temps.
            val scene = surFilAwt {
                ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
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
                                choisirFichierImport = { null },
                                choisirApk = { null },
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

        // La sauvegarde, relue contre le téléviseur qu'elle décrit, ne doit rien demander à
        // réinjecter. Tout se calcule en mémoire : aucune commande ne part vers le téléviseur.
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

        // Le stockage, lu comme l'onglet le ferait.
        pilote.rafraichirStockage()
        val stockageLu = attendre(40_000) { pilote.etat.value.lectureStockageTentee }
        val stockage = pilote.etat.value.stockage
        releves += "stockage: lu=$stockageLu, totalKo=${stockage.totalKo}, libreKo=${stockage.libreKo}, " +
            "applications=${stockage.applications.size}"
        assertTrue("Le stockage du téléviseur doit se lire", stockage.renseignee)

        // L'inventaire des inconnus, relu et écrit comme par le bouton d'export, à côté des captures. Aucune
        // fenêtre n'est ouverte à ce moment : le message de fin reste là pour dire comment ça s'est passé.
        val inventaire = File(sortie, "inconnus.md").apply { delete() }
        pilote.configuration.exporterInconnus(inventaire)
        val fins = setOf(Res.string.msg_unknown_exported, Res.string.msg_unknown_export_failed)
        val termine = attendre(60_000) { (pilote.etat.value.message as? MessageUi.Texte)?.ressource in fins }
        val rapport = inventaire.takeIf { it.isFile }?.readText().orEmpty()
        releves += "inconnus: ${lu.inconnus.size}, termine=$termine, message=${pilote.etat.value.message}"
        releves += rapport.lineSequence().filter { it.startsWith("- Lu sur") || it.startsWith("- Avec les droits") }.joinToString(" / ")
        assertTrue("L'inventaire doit s'écrire : ${pilote.etat.value.message}", rapport.isNotEmpty())
        assertTrue(
            "Les trois lectures doivent aboutir : ${rapport.take(800)}",
            rapport.contains("- Lu sur l'appareil : indices ADB, mémoire vive, stockage\n"),
        )

        // La section des inconnus, amenée en tête de liste par le nom de la première famille.
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
