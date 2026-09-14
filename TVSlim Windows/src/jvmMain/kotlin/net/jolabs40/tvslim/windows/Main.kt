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
import net.jolabs40.tvslim.windows.adb.ClientAdb
import net.jolabs40.tvslim.windows.adb.DepotCles
import net.jolabs40.tvslim.windows.data.PreferencesWindows
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
import net.jolabs40.tvslim.windows.ui.Onglet
import net.jolabs40.tvslim.windows.ui.PiloteApp
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
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
                    client.deconnecter()
                    exitApplication()
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
                client.deconnecter()
                exitApplication()
            },
            state = etatFenetre,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.ic_tvslim),
            onKeyEvent = { evenement ->
                // F5 relit le téléviseur, comme on rafraîchit une page.
                if (evenement.type == KeyEventType.KeyDown && evenement.key == Key.F5) {
                    if (onglet == Onglet.MEMOIRE) {
                        pilote.rafraichirMemoire()
                        pilote.rafraichirStockage()
                    } else {
                        pilote.rafraichir()
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
                    onglet = onglet,
                    onOnglet = { onglet = it },
                    ouvrirLien = ::ouvrirLien,
                    ouvrirDossierDonnees = { ouvrirDossier(emplacements.donnees) },
                    choisirFichierExport = { nom, titre -> choisirFichier(window, titre, nom) },
                    choisirFichierImport = { titre -> choisirFichierAOuvrir(window, titre) },
                    choisirApk = { titre ->
                        choisirFichierAOuvrir(window, titre, filtre = "*.apk", dossier = dossierTelechargements())
                    },
                )
            }
        }
    }
}

/** N'ouvre que des liens HTTPS, dans le navigateur de la personne. */
private fun ouvrirLien(lien: String) {
    if (!lien.startsWith("https://")) return
    thread(isDaemon = true, name = "ouverture-lien") {
        runCatching { Desktop.getDesktop().browse(URI(lien)) }
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

/** La fenêtre « Enregistrer sous » de Windows, ouverte sur le dossier Documents. */
private fun choisirFichier(parent: Frame, titre: String, nomPropose: String): File? {
    val dialogue = FileDialog(parent, titre, FileDialog.SAVE).apply {
        directory = dossierDocuments().path
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

/** Le vrai dossier Documents — souvent redirigé vers OneDrive — et non `~/Documents` supposé. */
private fun dossierDocuments(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Documents)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"))

/** Le vrai dossier Téléchargements, qui se déplace aussi ; Documents à défaut. */
private fun dossierTelechargements(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: dossierDocuments()
