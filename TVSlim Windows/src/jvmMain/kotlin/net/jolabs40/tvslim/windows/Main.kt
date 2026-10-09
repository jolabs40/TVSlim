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

/** Entry point. Objects are wired by hand: the shared core does not use Hilt and there are only a dozen of them. */
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
        // Screenshots and video go through TV Slim's ADB session (video is recorded on the TV); mirroring uses scrcpy.
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
                // Stop and copy a running recording first, so the recorder is not left running on the TV.
                ecran.fermer {
                    client.deconnecter()
                    exitApplication()
                }
            },
            state = etatFenetre,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.ic_tvslim),
            onKeyEvent = { evenement ->
                // F5 reloads the current tab from the TV.
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

/** Opens only `https://` links (browser) and `mailto:` addresses (mail client). */
private fun ouvrirLien(lien: String) {
    val courriel = lien.startsWith("mailto:")
    if (!lien.startsWith("https://") && !courriel) return
    thread(isDaemon = true, name = "ouverture-lien") {
        // Without a mail client nothing opens; the address is still shown on the button.
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

/** Native Save As dialog, in Documents by default. It asks before overwriting on its own. */
private fun choisirFichier(parent: Frame, titre: String, nomPropose: String, dossier: File = dossierDocuments()): File? {
    val dialogue = FileDialog(parent, titre, FileDialog.SAVE).apply {
        directory = dossier.path
        file = nomPropose
        isVisible = true // blocks until the user picks
    }
    val nom = dialogue.file ?: return null
    // Restore the suggested extension (.md, .json) if the user removed it.
    val extension = nomPropose.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    return File(
        dialogue.directory,
        if (extension.isEmpty() || nom.endsWith(extension, ignoreCase = true)) nom else "$nom$extension",
    )
}

/** Native Open dialog limited to one file type. */
private fun choisirFichierAOuvrir(
    parent: Frame,
    titre: String,
    filtre: String = "*.json",
    dossier: File = dossierDocuments(),
): File? {
    val dialogue = FileDialog(parent, titre, FileDialog.LOAD).apply {
        directory = dossier.path
        // The Windows dialog honours this pattern; setFilenameFilter is ignored there.
        file = filtre
        isVisible = true // blocks until the user picks
    }
    val nom = dialogue.file ?: return null
    return File(dialogue.directory, nom)
}

/** Native Open dialog with multiple selection, in Downloads. */
private fun choisirPlusieurs(parent: Frame, titre: String): List<File> {
    val dialogue = FileDialog(parent, titre, FileDialog.LOAD).apply {
        directory = dossierTelechargements().path
        isMultipleMode = true
        isVisible = true // blocks until the user picks
    }
    return dialogue.files.toList()
}

/**
 * Folder picker. AWT's `FileDialog` cannot pick a folder on Windows, so this uses Swing with the system look and
 * feel. Setting the look and feel affects nothing else: the app window is Compose, with no Swing component.
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

/** The real Documents folder (often redirected to OneDrive), not an assumed `~/Documents`. */
private fun dossierDocuments(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Documents)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"))

/** Pictures folder for screenshots, Documents as fallback. */
private fun dossierImages(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()

/** Videos folder for recordings, Documents as fallback. */
private fun dossierVideos(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Videos)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()

/** Downloads folder (it can be relocated too), Documents as fallback. */
private fun dossierTelechargements(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()
