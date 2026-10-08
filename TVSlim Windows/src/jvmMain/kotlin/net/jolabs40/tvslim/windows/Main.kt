package net.jolabs40.tvslim.windows

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.ecran.EnregistrementTv
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.DepotCles
import net.jolabs40.tvslim.windows.data.PreferencesWindows
import net.jolabs40.tvslim.windows.ecran.InstallationScrcpy
import net.jolabs40.tvslim.windows.ecran.LocalisationScrcpy
import net.jolabs40.tvslim.windows.maj.ClientGithub
import net.jolabs40.tvslim.windows.maj.Distribution
import net.jolabs40.tvslim.windows.maj.InstallateurMiseAJour
import net.jolabs40.tvslim.windows.maj.PiloteMisesAJour
import net.jolabs40.tvslim.windows.maj.Version
import net.jolabs40.tvslim.windows.outils.Traces
import net.jolabs40.tvslim.windows.reseau.DecouverteTv
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.app_name
import net.jolabs40.tvslim.windows.ressources.ic_tvslim
import net.jolabs40.tvslim.windows.ui.AppFenetre
import net.jolabs40.tvslim.windows.ui.CibleEcran
import net.jolabs40.tvslim.windows.ui.Onglet
import net.jolabs40.tvslim.windows.ui.PiloteApp
import net.jolabs40.tvslim.windows.ui.PiloteEcran
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.concurrent.thread

private const val TAG = "App"

/**
 * Point d'entrée. Les objets s'assemblent ici, à la main : le noyau partagé n'utilise pas Hilt,
 * et une fenêtre unique n'a pas besoin d'un conteneur d'injection pour une dizaine d'objets.
 */
fun main() {
    val emplacements = Emplacements.windows()
    Traces.ecrireDans(emplacements.traces)
    Traces.info(TAG, "Démarrage de TV Slim ${InfosApp.VERSION}")
    Thread.setDefaultUncaughtExceptionHandler { _, erreur ->
        Traces.avertir(TAG, "Erreur non rattrapée", erreur)
    }

    val client = ClientAdb(DepotCles(emplacements.cles))
    val preferences = PreferencesWindows(emplacements.preferences)
    val github = ClientGithub(depot = InfosApp.DEPOT_GITHUB, versionApp = InfosApp.VERSION)

    application {
        val pilote = remember {
            PiloteApp(client, CatalogueRepository(), preferences, DecouverteTv(), emplacements)
        }
        // Capture et vidéo par la session de TV Slim — la vidéo s'enregistre sur le téléviseur —, miroir par scrcpy.
        val ecran = remember {
            PiloteEcran(
                lecteur = client,
                enregistrement = EnregistrementTv(client, client),
                localisation = LocalisationScrcpy(emplacements.scrcpy),
                installation = InstallationScrcpy(github, emplacements.scrcpy),
                cible = {
                    pilote.etat.value.takeIf { it.connecte }
                        ?.let { CibleEcran(it.connexion.hote, it.connexion.port, it.infos) }
                },
                dossierImages = ::dossierImages,
                dossierVideos = ::dossierVideos,
            )
        }
        val misesAJour = remember {
            PiloteMisesAJour(
                preferences = preferences,
                client = github,
                installateur = InstallateurMiseAJour(
                    dossier = emplacements.telechargements,
                    client = github,
                    clePublique = InfosApp.CLE_PUBLIQUE_MISES_A_JOUR,
                ),
                distribution = Distribution.detecter(),
                versionActuelle = Version.lire(InfosApp.VERSION) ?: Version(0, 0, 0),
                depot = InfosApp.DEPOT_GITHUB,
                quitter = {
                    ecran.fermer {
                        client.deconnecter()
                        exitApplication()
                    }
                },
                ouvrirLien = ::ouvrirLien,
            )
        }
        var onglet by remember { mutableStateOf(Onglet.TELEVISEUR) }
        val etatFenetre = rememberWindowState(
            size = DpSize(1200.dp, 820.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                // Une vidéo en cours est d'abord copiée : elle ne resterait pas sur le téléviseur, enregistreur tournant.
                ecran.fermer {
                    client.deconnecter()
                    exitApplication()
                }
            },
            state = etatFenetre,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.ic_tvslim),
            onKeyEvent = { evenement ->
                // F5 relit le téléviseur, comme on rafraîchit une page.
                if (evenement.type == KeyEventType.KeyDown && evenement.key == Key.F5) {
                    when (onglet) {
                        Onglet.MEMOIRE -> {
                            pilote.rafraichirMemoire()
                            pilote.rafraichirStockage()
                        }

                        Onglet.FICHIERS -> pilote.fichiers.explorateur.actualiser()
                        Onglet.APPLICATIONS -> pilote.applications.charger()
                        else -> pilote.rafraichir()
                    }
                    true
                } else {
                    false
                }
            },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(960, 640) }
            TvSlimTheme {
                AppFenetre(
                    pilote = pilote,
                    misesAJour = misesAJour,
                    ecran = ecran,
                    dossierScrcpy = emplacements.scrcpy,
                    onglet = onglet,
                    onOnglet = { onglet = it },
                    ouvrirLien = ::ouvrirLien,
                    ouvrirDossierDonnees = { ouvrirDossier(emplacements.donnees) },
                    choisirFichierExport = { nom, titre -> choisirFichier(window, titre, nom) },
                    choisirFichierImport = { titre -> choisirFichierAOuvrir(window, titre) },
                    choisirApk = { titre ->
                        choisirFichierAOuvrir(window, titre, filtre = "*.apk", dossier = dossierTelechargements())
                    },
                    choisirFichiers = { titre -> choisirPlusieurs(window, titre) },
                    choisirDossier = { titre -> choisirDossier(window, titre) },
                    choisirDestinationFichier = { nom, titre ->
                        choisirFichier(window, titre, nom, dossier = dossierTelechargements())
                    },
                    choisirDestinationDossier = { titre ->
                        choisirDossier(window, titre, dossier = dossierTelechargements())
                    },
                    ouvrirDossier = ::ouvrirDossier,
                )
            }
        }
    }
}

/** N'ouvre que des liens HTTPS, dans le navigateur de la personne, et une adresse `mailto:`, dans sa messagerie. */
private fun ouvrirLien(lien: String) {
    val courriel = lien.startsWith("mailto:")
    if (!lien.startsWith("https://") && !courriel) return
    thread(isDaemon = true, name = "ouverture-lien") {
        // Sans messagerie installée, rien ne s'ouvre : l'adresse reste lisible sur le bouton.
        runCatching { if (courriel) Desktop.getDesktop().mail(URI(lien)) else Desktop.getDesktop().browse(URI(lien)) }
            .onFailure { Traces.avertir(TAG, "Lien non ouvert", it) }
    }
}

private fun ouvrirDossier(dossier: File) {
    thread(isDaemon = true, name = "ouverture-dossier") {
        runCatching {
            dossier.mkdirs()
            Desktop.getDesktop().open(dossier)
        }.onFailure { Traces.avertir(TAG, "Dossier non ouvert", it) }
    }
}

/**
 * La fenêtre « Enregistrer sous » de Windows, ouverte sur Documents — sur Téléchargements pour un fichier copié du
 * téléviseur. Elle demande d'elle-même s'il faut remplacer un fichier existant.
 */
private fun choisirFichier(parent: Frame, titre: String, nomPropose: String, dossier: File = dossierDocuments()): File? {
    val dialogue = FileDialog(parent, titre, FileDialog.SAVE).apply {
        directory = dossier.path
        file = nomPropose
        isVisible = true // bloquant jusqu'au choix
    }
    val nom = dialogue.file ?: return null
    // L'extension du nom proposé — .md pour le journal, .json pour une configuration — si on l'a ôtée.
    val extension = nomPropose.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    return File(
        dialogue.directory,
        if (extension.isEmpty() || nom.endsWith(extension, ignoreCase = true)) nom else "$nom$extension",
    )
}

/**
 * La fenêtre « Ouvrir » de Windows, limitée à un type de fichier : les configurations JSON, depuis
 * Documents ; les APK, depuis Téléchargements, où arrive ce qu'on vient de récupérer.
 */
private fun choisirFichierAOuvrir(
    parent: Frame,
    titre: String,
    filtre: String = "*.json",
    dossier: File = dossierDocuments(),
): File? {
    val dialogue = FileDialog(parent, titre, FileDialog.LOAD).apply {
        directory = dossier.path
        // Le filtre que respecte la fenêtre de Windows ; setFilenameFilter y est ignoré.
        file = filtre
        isVisible = true // bloquant jusqu'au choix
    }
    val nom = dialogue.file ?: return null
    return File(dialogue.directory, nom)
}

/** La fenêtre « Ouvrir » de Windows, plusieurs fichiers à la fois, depuis Téléchargements. */
private fun choisirPlusieurs(parent: Frame, titre: String): List<File> {
    val dialogue = FileDialog(parent, titre, FileDialog.LOAD).apply {
        directory = dossierTelechargements().path
        isMultipleMode = true
        isVisible = true // bloquant jusqu'au choix
    }
    return dialogue.files.toList()
}

/**
 * Le choix d'un dossier. Celle d'AWT ne sait pas en désigner un sous Windows : c'est donc la fenêtre de Swing,
 * à l'allure de Windows. Changer l'apparence de Swing ne touche à rien d'autre : la fenêtre de l'application
 * est dessinée par Compose, et n'a aucun composant Swing.
 */
private fun choisirDossier(parent: Frame, titre: String, dossier: File = dossierDocuments()): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        .onFailure { Traces.avertir(TAG, "Apparence de Windows indisponible", it) }
    val choix = JFileChooser(dossier).apply {
        dialogTitle = titre
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (choix.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) choix.selectedFile else null
}

/** Le vrai dossier Documents — souvent redirigé vers OneDrive — et non `~/Documents` supposé. */
private fun dossierDocuments(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Documents)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"))

/** Le vrai dossier Images, où vont les captures ; Documents à défaut. */
private fun dossierImages(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()

/** Le vrai dossier Vidéos, où vont les enregistrements ; Documents à défaut. */
private fun dossierVideos(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Videos)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()

/** Le vrai dossier Téléchargements, qui se déplace aussi ; Documents à défaut. */
private fun dossierTelechargements(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()
