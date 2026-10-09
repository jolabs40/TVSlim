package net.jolabs40.tvslim.remote.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.screens.ApplicationsUiActions
import net.jolabs40.tvslim.remote.ui.screens.FilesActions
import net.jolabs40.tvslim.remote.ui.screens.AboutDialog
import net.jolabs40.tvslim.remote.ui.screens.CapturePreviewDialog
import net.jolabs40.tvslim.remote.ui.screens.ApplicationsScreen
import net.jolabs40.tvslim.remote.ui.screens.SupportBanner
import net.jolabs40.tvslim.remote.ui.screens.AboutButton
import net.jolabs40.tvslim.remote.ui.screens.CaptureButton
import net.jolabs40.tvslim.remote.ui.screens.SupportButton
import net.jolabs40.tvslim.remote.ui.screens.ConfirmationDialog
import net.jolabs40.tvslim.remote.ui.screens.ConnectionScreen
import net.jolabs40.tvslim.remote.ui.screens.FilesScreen
import net.jolabs40.tvslim.remote.ui.screens.JournalScreen
import net.jolabs40.tvslim.remote.ui.screens.MemoryScreen
import net.jolabs40.tvslim.remote.ui.screens.PackagesScreen

private data class AppTab(val route: String, val title: Int, val icon: ImageVector)

private val tabs = listOf(
    AppTab("connexion", R.string.tab_connection, Icons.Filled.Cast),
    AppTab("paquets", R.string.tab_packages, Icons.Filled.Inventory2),
    AppTab("applications", R.string.tab_apps, Icons.Filled.Apps),
    AppTab("memoire", R.string.tab_memory, Icons.Filled.Memory),
    AppTab("fichiers", R.string.tab_files, Icons.Filled.Folder),
    AppTab("journal", R.string.tab_log, Icons.Filled.History),
)

/** Depending on the file manager, an APK is typed as an Android package or as plain binary. */
private val TYPES_APK = arrayOf("application/vnd.android.package-archive", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteApp() {
    val model: RemoteViewModel = hiltViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val supportVisible by model.support.visible.collectAsStateWithLifecycle()
    val captureState by model.capture.state.collectAsStateWithLifecycle()
    val tvAppState by model.tvApp.state.collectAsStateWithLifecycle()
    // Collected outside the Apps tab too: the permissions card uses it to pick an app.
    val globalApplicationsState by model.applications.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigation = rememberNavController()
    val backStackEntry by navigation.currentBackStackEntryAsState()
    val messages = remember { SnackbarHostState() }

    // An ADB session does not survive TV standby, nor always a long time in another app. On return,
    // silently retry the last TV; failure shows nothing.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.resumeConnection()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Every action result goes through the same snackbar, then is consumed.
    LaunchedEffect(state.message) {
        state.message?.let { text ->
            messages.showSnackbar(text)
            model.clearMessage()
        }
    }

    state.confirmation?.let { request ->
        ConfirmationDialog(
            confirmation = request,
            onConfirm = model::confirm,
            onCancel = {
                // A declined install leaves the APK copy in the cache; delete it.
                (request as? Confirmation.Installation)?.let { model.configuration.abandonApk(it.apk) }
                model.cancelConfirmation()
            },
        )
    }

    var about by rememberSaveable { mutableStateOf(false) }
    if (about) AboutDialog(onClose = { about = false })

    captureState.last?.let { last ->
        CapturePreviewDialog(
            capture = last,
            onShare = { runCatching { context.startActivity(model.capture.shareIntent(last)) } },
            onClose = model.capture::close,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Decorative: the name next to it says it all.
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.app_name))
                    }
                },
                actions = {
                    if (state.connected) CaptureButton(inProgress = captureState.inProgress, onCapture = model.capture::takeCapture)
                    SupportButton()
                    AboutButton(onOpen = { about = true })
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val chosen = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = chosen,
                        onClick = {
                            navigation.navigate(tab.route) {
                                popUpTo(navigation.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.title)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Above every tab, since the action that triggered it may come from any of them.
            SupportBanner(
                visible = supportVisible,
                onSupport = model.support::dismiss,
                onAlreadyDone = model.support::declareDonation,
                onLater = model.support::dismiss,
            )
            NavHost(
                navController = navigation,
                startDestination = "connexion",
                modifier = Modifier.weight(1f),
            ) {
                composable("connexion") {
                    // The Android picker needs no storage permission.
                    val apk = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let(model.configuration::chooseApk) }
                    ConnectionScreen(
                        state = state,
                        onHost = model::updateHost,
                        onPort = model::updatePort,
                        onConnect = model::connect,
                        onDisconnect = model::disconnect,
                        onRefresh = model::refresh,
                        onReboot = model::requestReboot,
                        onScan = model::applyScan,
                        onScanFailure = model::reportScanFailure,
                        onInstallLauncher = model.configuration::installLauncher,
                        onSetHome = model.configuration::setHome,
                        onRevertDrift = model.configuration::suggestDriftRevert,
                        onSearch = model::searchDevices,
                        onStopSearch = model::stopSearch,
                        onConnectTo = model::connectTo,
                        permissionsActions = PermissionsActions(
                            onPackage = model.permissions::updatePackage,
                            onPermission = model.permissions::updatePermission,
                            onRead = model.permissions::read,
                            onGrant = model.permissions::grant,
                            onRevoke = model.permissions::revoke,
                            onChoosePackage = model.permissions::choosePackage,
                            onLoadApps = model.applications::load,
                        ),
                        applicationsState = globalApplicationsState,
                        tvAppState = tvAppState,
                        tvAppActions = TvAppActions(
                            onRead = model.tvApp::read,
                            onInstall = model.tvApp::install,
                            onAuthorize = model.tvApp::authorize,
                        ),
                        onChooseApk = { apk.launch(TYPES_APK) },
                        commandActions = CommandActions(
                            onInput = model.configuration::enterCommand,
                            onSend = model.configuration::sendCommand,
                        ),
                        actionsShizuku = ShizukuActions(
                            onRelaunch = model.configuration::relaunchShizuku,
                        ),
                    )
                }
                composable("paquets") {
                    // Files are only read or written where the user picked them.
                    val backup = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/json"),
                    ) { uri -> uri?.let(model.configuration::save) }
                    val reinjection = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri -> uri?.let(model.configuration::load) }
                    val inventory = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("text/markdown"),
                    ) { uri -> uri?.let { model.configuration.exportUnknowns(it) } }
                    // Same export, then the catalogue form in the browser.
                    val suggestion = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("text/markdown"),
                    ) { uri -> uri?.let { model.configuration.exportUnknowns(it, suggest = true) } }
                    PackagesScreen(
                        state = state,
                        onToggle = model::toggleSelection,
                        onProfileSelected = model::selectProfile,
                        onUncheckAll = model::deselectAll,
                        onApply = model::requestApply,
                        onEnable = { model.enable(listOf(it)) },
                        onSearchChange = model::updateSearch,
                        onFilterSelected = model::updateFilter,
                        onSave = { backup.launch(model.configuration.fileName()) },
                        // Depending on the file manager, a .json file is typed as text or as binary.
                        onReinject = {
                            reinjection.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        },
                        onExportUnknowns = { inventory.launch(model.configuration.unknownsExportName()) },
                        onSuggestUnknowns = { suggestion.launch(model.configuration.unknownsExportName()) },
                    )
                }
                composable("applications") {
                    val applicationsState by model.applications.state.collectAsStateWithLifecycle()
                    val applications = model.applications
                    ApplicationsScreen(
                        connected = state.connected,
                        state = applicationsState,
                        actions = remember(applications) {
                            ApplicationsUiActions(
                                onLoad = applications::load,
                                onSearchChange = applications::updateSearch,
                                onChoose = applications::choose,
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
                }
                composable("memoire") {
                    MemoryScreen(
                        state = state,
                        onRefresh = model::refreshMemory,
                        onRefreshStorage = model::refreshStorage,
                        onForceStop = model::forceStop,
                        onResetReference = model::resetReference,
                    )
                }
                composable("fichiers") {
                    val filesState by model.files.explorer.state.collectAsStateWithLifecycle()
                    val pending by model.files.pending.collectAsStateWithLifecycle()
                    val explorer = model.files.explorer
                    // The Android picker needs no storage permission, and each document is read only when sent.
                    // Picked items then wait for the user to open their destination.
                    val documents = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenMultipleDocuments(),
                    ) { uris -> model.files.chooseDocuments(uris) }
                    val folder = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri -> uri?.let(model.files::chooseFolder) }
                    FilesScreen(
                        connected = state.connected,
                        state = filesState,
                        pending = pending,
                        actions = FilesActions(
                            onStart = explorer::start,
                            onOpen = explorer::open,
                            onGoUp = explorer::goUp,
                            onRefresh = explorer::refresh,
                            onUploadFiles = { documents.launch(arrayOf("*/*")) },
                            onUploadFolder = { folder.launch(null) },
                            onCreateFolder = explorer::createFolder,
                            onConfirm = model.files::confirm,
                            onCancelConfirmation = explorer::cancelConfirmation,
                            onStop = explorer::cancelUpload,
                            onUploadHere = model.files::uploadHere,
                            onAbandonUpload = model.files::abandonUpload,
                        ),
                    )
                }
                composable("journal") {
                    JournalScreen(
                        state = state,
                        onRestoreAll = model::requestRestore,
                        onExport = model::exportJournal,
                        onUndoAction = model::undoAction,
                    )
                }
            }
        }
    }
}
