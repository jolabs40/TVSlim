package net.jolabs40.tvslim.windows.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jolabs40.tvslim.files.RemoteEntry
import net.jolabs40.tvslim.support.SupportInvitation
import net.jolabs40.tvslim.windows.AppInfo
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.update.UpdatesController
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.about_contact_address
import net.jolabs40.tvslim.windows.resources.about_title
import net.jolabs40.tvslim.windows.resources.about_website_url
import net.jolabs40.tvslim.windows.resources.app_name
import net.jolabs40.tvslim.windows.resources.baseline_apps_24
import net.jolabs40.tvslim.windows.resources.baseline_cast_24
import net.jolabs40.tvslim.windows.resources.baseline_folder_24
import net.jolabs40.tvslim.windows.resources.baseline_history_24
import net.jolabs40.tvslim.windows.resources.baseline_info_24
import net.jolabs40.tvslim.windows.resources.baseline_inventory_2_24
import net.jolabs40.tvslim.windows.resources.baseline_memory_24
import net.jolabs40.tvslim.windows.resources.config_open_dialog
import net.jolabs40.tvslim.windows.resources.config_save_dialog
import net.jolabs40.tvslim.windows.resources.files_pick_destination_file
import net.jolabs40.tvslim.windows.resources.files_pick_destination_folder
import net.jolabs40.tvslim.windows.resources.files_pick_files
import net.jolabs40.tvslim.windows.resources.files_pick_folder
import net.jolabs40.tvslim.windows.resources.install_dialog
import net.jolabs40.tvslim.windows.resources.journal_export_dialog
import net.jolabs40.tvslim.windows.resources.scrcpy_window_mirror
import net.jolabs40.tvslim.windows.resources.status_connected
import net.jolabs40.tvslim.windows.resources.status_connecting
import net.jolabs40.tvslim.windows.resources.status_disconnected
import net.jolabs40.tvslim.windows.resources.status_error
import net.jolabs40.tvslim.windows.resources.tab_apps
import net.jolabs40.tvslim.windows.resources.tab_connection
import net.jolabs40.tvslim.windows.resources.tab_files
import net.jolabs40.tvslim.windows.resources.tab_log
import net.jolabs40.tvslim.windows.resources.tab_memory
import net.jolabs40.tvslim.windows.resources.tab_packages
import net.jolabs40.tvslim.windows.resources.unknown_export_dialog
import net.jolabs40.tvslim.windows.ui.screens.AboutDialog
import net.jolabs40.tvslim.windows.ui.screens.ApplicationsUiActions
import net.jolabs40.tvslim.windows.ui.screens.ScreenActions
import net.jolabs40.tvslim.windows.ui.screens.FilesActions
import net.jolabs40.tvslim.windows.ui.screens.CapturePreviewDialog
import net.jolabs40.tvslim.windows.ui.screens.ApplicationsScreen
import net.jolabs40.tvslim.windows.ui.screens.UpdateBanner
import net.jolabs40.tvslim.windows.ui.screens.SupportBanner
import net.jolabs40.tvslim.windows.ui.screens.SupportButton
import net.jolabs40.tvslim.windows.ui.screens.ConfirmationDialog
import net.jolabs40.tvslim.windows.ui.screens.ConnectionScreen
import net.jolabs40.tvslim.windows.ui.screens.ClosingDialog
import net.jolabs40.tvslim.windows.ui.screens.FilesScreen
import net.jolabs40.tvslim.windows.ui.screens.JournalScreen
import net.jolabs40.tvslim.windows.ui.screens.MemoryScreen
import net.jolabs40.tvslim.windows.ui.screens.PackagesScreen
import net.jolabs40.tvslim.windows.ui.screens.ScrcpyDownloadDialog
import net.jolabs40.tvslim.windows.ui.screens.RecordedVideoDialog
import net.jolabs40.tvslim.windows.ui.screens.UploadOverlay
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.net.URI

/** Same tabs, order and icons as the Android companion. */
enum class AppTab(val title: StringResource, val icon: DrawableResource) {
    TV(Res.string.tab_connection, Res.drawable.baseline_cast_24),
    PACKAGES(Res.string.tab_packages, Res.drawable.baseline_inventory_2_24),
    APPLICATIONS(Res.string.tab_apps, Res.drawable.baseline_apps_24),
    MEMORY(Res.string.tab_memory, Res.drawable.baseline_memory_24),
    FILES(Res.string.tab_files, Res.drawable.baseline_folder_24),
    JOURNAL(Res.string.tab_log, Res.drawable.baseline_history_24),
}

/** Main window. The left rail stands in for the phone's bottom navigation bar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun AppWindow(
    controller: AppController,
    updates: UpdatesController,
    /** Screenshot, mirroring and screen recording. */
    screen: ScreenController,
    /** Where scrcpy gets downloaded; shown in the download prompt. */
    scrcpyFolder: File,
    tab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    openLink: (String) -> Unit,
    openDataFolder: () -> Unit,
    chooseExportFile: (suggestedName: String, title: String) -> File?,
    chooseImportFile: (title: String) -> File?,
    chooseApk: (title: String) -> File?,
    chooseFiles: (title: String) -> List<File>,
    chooseFolder: (title: String) -> File?,
    /** Save dialog, opened on Downloads, for a file copied from the TV. */
    chooseFileDestination: (suggestedName: String, title: String) -> File?,
    /** Picks, starting in Downloads, the folder that receives a folder copied from the TV. */
    chooseFolderDestination: (title: String) -> File?,
    openFolder: (File) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val filesState by controller.files.explorer.state.collectAsStateWithLifecycle()
    val applicationsState by controller.applications.state.collectAsStateWithLifecycle()
    val tvAppState by controller.tvApp.state.collectAsStateWithLifecycle()
    val updatesState by updates.state.collectAsStateWithLifecycle()
    val supportVisible by controller.support.visible.collectAsStateWithLifecycle()
    val screenState by screen.state.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    var about by remember { mutableStateOf(false) }
    val exportTitle = stringResource(Res.string.journal_export_dialog)
    val saveTitle = stringResource(Res.string.config_save_dialog)
    val reinjectionTitle = stringResource(Res.string.config_open_dialog)
    val unknownsTitle = stringResource(Res.string.unknown_export_dialog)
    val apkTitle = stringResource(Res.string.install_dialog)
    val filesTitle = stringResource(Res.string.files_pick_files)
    val folderTitle = stringResource(Res.string.files_pick_folder)
    val destinationFileTitle = stringResource(Res.string.files_pick_destination_file)
    val destinationFolderTitle = stringResource(Res.string.files_pick_destination_folder)
    // Names the scrcpy window so it can be found in the taskbar.
    val tvName = state.info.displayName.ifBlank { state.connection.host }
    val mirrorTitle = stringResource(Res.string.scrcpy_window_mirror, tvName)

    // Drops are accepted anywhere in the window, since the install card may not be on screen. On the Files
    // tab everything dropped (APKs included) is uploaded to the current folder; elsewhere it is an APK to install.
    var hover by remember { mutableStateOf(false) }
    val currentTab by rememberUpdatedState(tab)
    val dropTarget = remember(controller) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                hover = true
            }

            override fun onExited(event: DragAndDropEvent) {
                hover = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hover = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hover = false
                val links = (event.dragData() as? DragData.FilesList)?.readFiles().orEmpty()
                val files = links.mapNotNull { link -> runCatching { File(URI(link)) }.getOrNull() }
                if (files.isEmpty()) return false
                if (currentTab == AppTab.FILES) {
                    if (!controller.state.value.connected) return false
                    controller.files.upload(files)
                } else {
                    controller.configuration.chooseApk(files.first())
                }
                return true
            }
        }
    }

    // ADB sessions do not survive TV standby: silently retry the last TV when the window is restored.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) controller.resumeConnection()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = message.phrase()
        messages.showSnackbar(
            message = text,
            withDismissAction = true,
            duration = if ('\n' in text) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        controller.clearMessage()
    }
    LaunchedEffect(screenState.message) {
        val message = screenState.message ?: return@LaunchedEffect
        messages.showSnackbar(message = message.phrase(), withDismissAction = true)
        screen.clearMessage()
    }

    screenState.capture?.let { capture ->
        CapturePreviewDialog(
            capture = capture,
            onCopy = screen::copyCapture,
            onOpenFolder = { capture.file.parentFile?.let(openFolder) },
            onClose = screen::closeCapture,
        )
    }
    if (screenState.offeredDownload != null) {
        ScrcpyDownloadDialog(
            phase = screenState.scrcpy,
            folder = scrcpyFolder,
            onDownload = screen::acceptDownload,
            onCancel = screen::declineDownload,
        )
    }
    if (screenState.closing) ClosingDialog(screenState.recording)
    screenState.video?.let { video ->
        RecordedVideoDialog(
            video = video,
            onOpenFolder = { video.parentFile?.let(openFolder) },
            onClose = screen::closeVideo,
        )
    }

    state.confirmation?.let { request ->
        ConfirmationDialog(
            confirmation = request,
            onConfirm = controller::confirm,
            onCancel = controller::cancelConfirmation,
        )
    }

    val website = stringResource(Res.string.about_website_url)
    val contact = stringResource(Res.string.about_contact_address)
    if (about) {
        AboutDialog(
            state = updatesState,
            onClose = { about = false },
            onCheck = { updates.check() },
            onVerificationAuto = updates::setAutoCheck,
            onInstall = updates::install,
            // Localized URL: English site at the root, French under /fr/.
            onSite = { openLink(website) },
            onContact = { openLink("mailto:$contact") },
            onSource = { openLink("https://github.com/${AppInfo.GITHUB_REPOSITORY}") },
            onSupport = { openLink(SupportInvitation.LINK) },
            onFolder = openDataFolder,
        )
    }

    val tvAppActions = remember(controller) {
        TvAppActions(
            onRead = controller.tvApp::read,
            onInstall = controller.tvApp::install,
            onAuthorize = controller.tvApp::authorize,
        )
    }
    val permissionsActions = remember(controller) {
        PermissionsActions(
            onPackage = controller.permissions::updatePackage,
            onPermission = controller.permissions::updatePermission,
            onRead = controller.permissions::read,
            onGrant = controller.permissions::grant,
            onRevoke = controller.permissions::revoke,
            onChoosePackage = controller.permissions::choosePackage,
            onLoadApps = controller.applications::load,
        )
    }
    val commandActions = remember(controller) {
        CommandActions(
            onInput = controller.configuration::enterCommand,
            onSend = controller.configuration::sendCommand,
            onRecall = controller.configuration::recallCommand,
        )
    }

    Scaffold(
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { it.dragData() is DragData.FilesList },
            target = dropTarget,
        ),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                actions = {
                    ScreenActions(
                        state = screenState,
                        connected = state.connected,
                        onCapture = screen::takeCapture,
                        onMirror = { screen.openMirror(mirrorTitle) },
                        onStopMirror = screen::stopMirror,
                        onRecord = screen::record,
                        onStopRecording = screen::stopRecording,
                    )
                    ConnectionDot(state)
                    SupportButton(onClick = { openLink(SupportInvitation.LINK) })
                    IconButton(onClick = { about = true }) {
                        Icon(
                            painter = painterResource(Res.drawable.baseline_info_24),
                            contentDescription = stringResource(Res.string.about_title),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(8.dp))
                AppTab.entries.forEach { target ->
                    NavigationRailItem(
                        selected = target == tab,
                        onClick = { onTabSelected(target) },
                        icon = { Icon(painterResource(target.icon), contentDescription = null) },
                        label = { Text(stringResource(target.title)) },
                    )
                }
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                UpdateBanner(
                    state = updatesState,
                    onInstall = updates::install,
                    onPage = updates::openPage,
                    onLater = updates::dismiss,
                )
                SupportBanner(
                    visible = supportVisible,
                    onSupport = {
                        openLink(SupportInvitation.LINK)
                        controller.support.dismiss()
                    },
                    onAlreadyDone = controller.support::declareDonation,
                    onLater = controller.support::dismiss,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AppTab.TV -> ConnectionScreen(
                            state = state,
                            onHost = controller::updateHost,
                            onPort = controller::updatePort,
                            onConnect = controller::connect,
                            onDisconnect = controller::disconnect,
                            onRefresh = controller::refresh,
                            onInstallLauncher = controller.configuration::installLauncher,
                            onSetHome = controller.configuration::setHome,
                            onOpenLink = openLink,
                            onRevertDrift = controller.configuration::suggestDriftRevert,
                            onSearch = controller::searchDevices,
                            onStopSearch = controller::stopSearch,
                            onConnectTo = controller::connectTo,
                            permissionsActions = permissionsActions,
                            applicationsState = applicationsState,
                            tvAppState = tvAppState,
                            tvAppActions = tvAppActions,
                            onChooseApk = { chooseApk(apkTitle)?.let(controller.configuration::chooseApk) },
                            commandActions = commandActions,
                            onReboot = controller::requestReboot,
                        )

                        AppTab.PACKAGES -> PackagesScreen(
                            state = state,
                            onToggle = controller::toggleSelection,
                            onShowDetails = controller::showDetails,
                            onProfileSelected = controller::selectProfile,
                            onUncheckAll = controller::deselectAll,
                            onApply = controller::requestApply,
                            onEnable = { controller.enable(listOf(it)) },
                            onSearchChange = controller::updateSearch,
                            onFilterSelected = controller::updateFilter,
                            onSave = {
                                chooseExportFile(controller.configuration.fileName(), saveTitle)
                                    ?.let(controller.configuration::save)
                            },
                            onReinject = {
                                chooseImportFile(reinjectionTitle)?.let(controller.configuration::load)
                            },
                            onExportUnknowns = {
                                chooseExportFile(controller.configuration.unknownsExportName(), unknownsTitle)
                                    ?.let { controller.configuration.exportUnknowns(it) }
                            },
                            // Same export, then opens the catalogue submission form in the browser.
                            onSuggestUnknowns = {
                                chooseExportFile(controller.configuration.unknownsExportName(), unknownsTitle)
                                    ?.let { controller.configuration.exportUnknowns(it, thenOpen = openLink) }
                            },
                        )

                        AppTab.APPLICATIONS -> ApplicationsScreen(
                            connected = state.connected,
                            state = applicationsState,
                            actions = remember(controller) {
                                val applications = controller.applications
                                ApplicationsUiActions(
                                    onLoad = applications::load,
                                    onSearchChange = applications::updateSearch,
                                    onOpen = applications::open,
                                    onStop = applications::forceStop,
                                    onDisable = applications::requestDisable,
                                    onEnable = applications::enable,
                                    onUninstall = applications::requestUninstall,
                                    onConfirm = applications::confirm,
                                    onCancel = applications::cancelConfirmation,
                                    disableable = applications::disableableEntry,
                                )
                            },
                        )

                        AppTab.MEMORY -> MemoryScreen(
                            state = state,
                            onRefresh = controller::refreshMemory,
                            onRefreshStorage = controller::refreshStorage,
                            onForceStop = controller::forceStop,
                            onResetReference = controller::resetReference,
                        )

                        AppTab.FILES -> FilesScreen(
                            connected = state.connected,
                            state = filesState,
                            actions = fileActions(
                                controller = controller.files,
                                onUploadFiles = {
                                    chooseFiles(filesTitle).takeIf { it.isNotEmpty() }
                                        ?.let(controller.files::upload)
                                },
                                onUploadFolder = {
                                    chooseFolder(folderTitle)?.let { controller.files.upload(listOf(it)) }
                                },
                                onCopy = { entry ->
                                    val choice = if (entry.folder) {
                                        chooseFolderDestination(destinationFolderTitle)
                                    } else {
                                        chooseFileDestination(entry.name, destinationFileTitle)
                                    }
                                    choice?.let { controller.files.copy(entry, it) }
                                },
                                onOpenLocalFolder = { path -> openFolder(File(path)) },
                            ),
                        )

                        AppTab.JOURNAL -> JournalScreen(
                            state = state,
                            onRestoreAll = controller::requestRestore,
                            onExport = {
                                chooseExportFile(controller.journalExportName(), exportTitle)
                                    ?.let(controller::exportJournal)
                            },
                            onUndoAction = controller::undoAction,
                        )
                    }
                    if (hover) {
                        UploadOverlay(
                            connected = state.connected,
                            tvName = state.info.displayName.ifBlank { state.connection.host },
                            destination = filesState.path.takeIf { tab == AppTab.FILES },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun fileActions(
    controller: FilesController,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onCopy: (RemoteEntry) -> Unit,
    onOpenLocalFolder: (String) -> Unit,
): FilesActions {
    val explorer = controller.explorer
    return remember(controller) {
        FilesActions(
            onStart = explorer::start,
            onOpen = explorer::open,
            onGoUp = explorer::goUp,
            onRefresh = explorer::refresh,
            onUploadFiles = onUploadFiles,
            onUploadFolder = onUploadFolder,
            onCreateFolder = explorer::createFolder,
            onConfirm = explorer::confirm,
            onCancelConfirmation = explorer::cancelConfirmation,
            onStop = explorer::cancelUpload,
            onCopy = onCopy,
            onConfirmCopy = explorer::confirmDownload,
            onCancelCopy = explorer::cancelDownload,
            onDelete = explorer::requestDeletion,
            onConfirmDeletion = explorer::confirmDeletion,
            onCancelDeletion = explorer::cancelDeletion,
            onOpenLocalFolder = onOpenLocalFolder,
        )
    }
}

/** Connection status, shown on every tab. */
@Composable
private fun ConnectionDot(state: AppState) {
    val connection = state.connection
    val name = state.info.displayName.ifBlank { state.knownNames[connection.host] ?: connection.host }
    val (text, color) = when (connection.state) {
        ConnectionState.CONNECTED ->
            stringResource(Res.string.status_connected, name) to MaterialTheme.colorScheme.primary

        ConnectionState.CONNECTION ->
            stringResource(Res.string.status_connecting, connection.host) to MaterialTheme.colorScheme.tertiary

        ConnectionState.ERROR ->
            stringResource(Res.string.status_error, connection.host) to MaterialTheme.colorScheme.error

        ConnectionState.DISCONNECTED ->
            stringResource(Res.string.status_disconnected) to MaterialTheme.colorScheme.outline
    }
    Row(modifier = Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
