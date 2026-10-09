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
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.screen.TvRecording
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.AdbKeyStore
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.screen.ScrcpyInstallation
import net.jolabs40.tvslim.windows.screen.ScrcpyLocator
import net.jolabs40.tvslim.windows.update.GithubClient
import net.jolabs40.tvslim.windows.update.Distribution
import net.jolabs40.tvslim.windows.update.UpdateInstaller
import net.jolabs40.tvslim.windows.update.UpdatesController
import net.jolabs40.tvslim.windows.update.Version
import net.jolabs40.tvslim.windows.tools.AppLog
import net.jolabs40.tvslim.windows.network.TvDiscovery
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.app_name
import net.jolabs40.tvslim.windows.resources.ic_tvslim
import net.jolabs40.tvslim.windows.ui.AppWindow
import net.jolabs40.tvslim.windows.ui.ScreenTarget
import net.jolabs40.tvslim.windows.ui.AppTab
import net.jolabs40.tvslim.windows.ui.AppController
import net.jolabs40.tvslim.windows.ui.ScreenController
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
    val locations = Locations.windows()
    AppLog.writeTo(locations.traces)
    AppLog.info(TAG, "Démarrage de TV Slim ${AppInfo.VERSION}")
    Thread.setDefaultUncaughtExceptionHandler { _, error ->
        AppLog.warn(TAG, "Erreur non rattrapée", error)
    }

    val client = AdbClient(AdbKeyStore(locations.keys))
    val preferences = WindowsPreferences(locations.preferences)
    val github = GithubClient(repository = AppInfo.GITHUB_REPOSITORY, appVersion = AppInfo.VERSION)

    application {
        val controller = remember {
            AppController(client, CatalogRepository(), preferences, TvDiscovery(), locations)
        }
        // Screenshots and video go through TV Slim's ADB session (video is recorded on the TV); mirroring uses scrcpy.
        val screen = remember {
            ScreenController(
                reader = client,
                recording = TvRecording(client, client),
                locator = ScrcpyLocator(locations.scrcpy),
                installation = ScrcpyInstallation(github, locations.scrcpy),
                target = {
                    controller.state.value.takeIf { it.connected }
                        ?.let { ScreenTarget(it.connection.host, it.connection.port, it.info) }
                },
                picturesFolder = ::picturesFolder,
                videosFolder = ::videosFolder,
            )
        }
        val updates = remember {
            UpdatesController(
                preferences = preferences,
                client = github,
                installer = UpdateInstaller(
                    folder = locations.downloads,
                    client = github,
                    publicKeyBase64 = AppInfo.UPDATE_PUBLIC_KEY,
                ),
                distribution = Distribution.detect(),
                currentVersion = Version.read(AppInfo.VERSION) ?: Version(0, 0, 0),
                repository = AppInfo.GITHUB_REPOSITORY,
                quit = {
                    screen.close {
                        client.disconnect()
                        exitApplication()
                    }
                },
                openLink = ::openLink,
            )
        }
        var tab by remember { mutableStateOf(AppTab.TV) }
        val windowState = rememberWindowState(
            size = DpSize(1200.dp, 820.dp),
            position = WindowPosition(Alignment.Center),
        )

        Window(
            onCloseRequest = {
                // Stop and copy a running recording first, so the recorder is not left running on the TV.
                screen.close {
                    client.disconnect()
                    exitApplication()
                }
            },
            state = windowState,
            title = stringResource(Res.string.app_name),
            icon = painterResource(Res.drawable.ic_tvslim),
            onKeyEvent = { event ->
                // F5 reloads the current tab from the TV.
                if (event.type == KeyEventType.KeyDown && event.key == Key.F5) {
                    when (tab) {
                        AppTab.MEMORY -> {
                            controller.refreshMemory()
                            controller.refreshStorage()
                        }

                        AppTab.FILES -> controller.files.explorer.refresh()
                        AppTab.APPLICATIONS -> controller.applications.load()
                        else -> controller.refresh()
                    }
                    true
                } else {
                    false
                }
            },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(960, 640) }
            TvSlimTheme {
                AppWindow(
                    controller = controller,
                    updates = updates,
                    screen = screen,
                    scrcpyFolder = locations.scrcpy,
                    tab = tab,
                    onTabSelected = { tab = it },
                    openLink = ::openLink,
                    openDataFolder = { openFolder(locations.data) },
                    chooseExportFile = { name, title -> chooseFile(window, title, name) },
                    chooseImportFile = { title -> chooseFileToOpen(window, title) },
                    chooseApk = { title ->
                        chooseFileToOpen(window, title, filter = "*.apk", folder = downloadsFolder())
                    },
                    chooseFiles = { title -> chooseMultiple(window, title) },
                    chooseFolder = { title -> chooseFolder(window, title) },
                    chooseFileDestination = { name, title ->
                        chooseFile(window, title, name, folder = downloadsFolder())
                    },
                    chooseFolderDestination = { title ->
                        chooseFolder(window, title, folder = downloadsFolder())
                    },
                    openFolder = ::openFolder,
                )
            }
        }
    }
}

/** Opens only `https://` links (browser) and `mailto:` addresses (mail client). */
private fun openLink(link: String) {
    val isEmail = link.startsWith("mailto:")
    if (!link.startsWith("https://") && !isEmail) return
    thread(isDaemon = true, name = "ouverture-lien") {
        // Without a mail client nothing opens; the address is still shown on the button.
        runCatching { if (isEmail) Desktop.getDesktop().mail(URI(link)) else Desktop.getDesktop().browse(URI(link)) }
            .onFailure { AppLog.warn(TAG, "Lien non ouvert", it) }
    }
}

private fun openFolder(folder: File) {
    thread(isDaemon = true, name = "ouverture-dossier") {
        runCatching {
            folder.mkdirs()
            Desktop.getDesktop().open(folder)
        }.onFailure { AppLog.warn(TAG, "Dossier non ouvert", it) }
    }
}

/** Native Save As dialog, in Documents by default. It asks before overwriting on its own. */
private fun chooseFile(parent: Frame, title: String, suggestedName: String, folder: File = documentsFolder()): File? {
    val dialog = FileDialog(parent, title, FileDialog.SAVE).apply {
        directory = folder.path
        file = suggestedName
        isVisible = true // blocks until the user picks
    }
    val name = dialog.file ?: return null
    // Restore the suggested extension (.md, .json) if the user removed it.
    val extension = suggestedName.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
    return File(
        dialog.directory,
        if (extension.isEmpty() || name.endsWith(extension, ignoreCase = true)) name else "$name$extension",
    )
}

/** Native Open dialog limited to one file type. */
private fun chooseFileToOpen(
    parent: Frame,
    title: String,
    filter: String = "*.json",
    folder: File = documentsFolder(),
): File? {
    val dialog = FileDialog(parent, title, FileDialog.LOAD).apply {
        directory = folder.path
        // The Windows dialog honours this pattern; setFilenameFilter is ignored there.
        file = filter
        isVisible = true // blocks until the user picks
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** Native Open dialog with multiple selection, in Downloads. */
private fun chooseMultiple(parent: Frame, title: String): List<File> {
    val dialog = FileDialog(parent, title, FileDialog.LOAD).apply {
        directory = downloadsFolder().path
        isMultipleMode = true
        isVisible = true // blocks until the user picks
    }
    return dialog.files.toList()
}

/**
 * Folder picker. AWT's `FileDialog` cannot pick a folder on Windows, so this uses Swing with the system look and
 * feel. Setting the look and feel affects nothing else: the app window is Compose, with no Swing component.
 */
private fun chooseFolder(parent: Frame, title: String, folder: File = documentsFolder()): File? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        .onFailure { AppLog.warn(TAG, "Apparence de Windows indisponible", it) }
    val choice = JFileChooser(folder).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (choice.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) choice.selectedFile else null
}

/** The real Documents folder (often redirected to OneDrive), not an assumed `~/Documents`. */
private fun documentsFolder(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Documents)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"))

/** Pictures folder for screenshots, Documents as fallback. */
private fun picturesFolder(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: documentsFolder()

/** Videos folder for recordings, Documents as fallback. */
private fun videosFolder(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Videos)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: documentsFolder()

/** Downloads folder (it can be relocated too), Documents as fallback. */
private fun downloadsFolder(): File =
    runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)) }
        .getOrNull()
        ?.takeIf { it.isDirectory }
        ?: documentsFolder()
