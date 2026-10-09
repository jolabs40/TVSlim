package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.windows.ui.screens.PermissionsCard
import net.jolabs40.tvslim.device.PackagePermissions
import net.jolabs40.tvslim.tvapp.TvRelease
import net.jolabs40.tvslim.tvapp.TvSituation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.runBlocking
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.command.CommandExchange
import net.jolabs40.tvslim.configuration.SavedDevice
import net.jolabs40.tvslim.configuration.HomeChange
import net.jolabs40.tvslim.configuration.TvConfiguration
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.device.FactoryHome
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.Manufacturer
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.device.InstalledLauncher
import net.jolabs40.tvslim.device.PackageOrigin
import net.jolabs40.tvslim.device.StorageBreakdown
import net.jolabs40.tvslim.device.ApplicationStorage
import net.jolabs40.tvslim.device.origin
import net.jolabs40.tvslim.device.unknownPackages
import net.jolabs40.tvslim.files.UploadProgress
import net.jolabs40.tvslim.files.LocalTarget
import net.jolabs40.tvslim.files.UploadFailure
import net.jolabs40.tvslim.files.RemoteEntry
import net.jolabs40.tvslim.files.ExplorerState
import net.jolabs40.tvslim.files.RemoteFile
import net.jolabs40.tvslim.files.LocalFile
import net.jolabs40.tvslim.files.FolderRead
import net.jolabs40.tvslim.files.LocalBatch
import net.jolabs40.tvslim.files.EntryKind
import net.jolabs40.tvslim.files.DeletionKind
import net.jolabs40.tvslim.files.UploadPlan
import net.jolabs40.tvslim.files.DownloadPlan
import net.jolabs40.tvslim.files.DeletionPlan
import net.jolabs40.tvslim.files.Shortcut
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.files.TransferDirection
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.FailureCause
import net.jolabs40.tvslim.installation.ApkManifest
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.installation.InstalledVersion
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.windows.adb.ConnectionUi
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.network.DiscoveredDevice
import net.jolabs40.tvslim.windows.network.DiscoveryResult
import net.jolabs40.tvslim.windows.ui.components.LOGOS_LAUNCHERS
import net.jolabs40.tvslim.windows.ui.components.LogoLauncher
import net.jolabs40.tvslim.windows.ui.components.BrandPlate
import net.jolabs40.tvslim.windows.update.UpdateState
import net.jolabs40.tvslim.windows.ui.screens.AboutDialog
import net.jolabs40.tvslim.windows.ui.screens.ScreenActions
import net.jolabs40.tvslim.windows.ui.screens.FilesActions
import net.jolabs40.tvslim.windows.ui.screens.CapturePreviewDialog
import net.jolabs40.tvslim.windows.ui.screens.SupportBanner
import net.jolabs40.tvslim.windows.ui.screens.SupportButton
import net.jolabs40.tvslim.windows.ui.screens.HomeCard
import net.jolabs40.tvslim.windows.ui.screens.DeviceCard
import net.jolabs40.tvslim.windows.ui.screens.CommandCard
import net.jolabs40.tvslim.windows.ui.screens.InstallationCard
import net.jolabs40.tvslim.windows.ui.screens.UploadConfirmation
import net.jolabs40.tvslim.windows.ui.screens.ConfirmationDialog
import net.jolabs40.tvslim.windows.ui.screens.DownloadConfirmation
import net.jolabs40.tvslim.windows.ui.screens.DeletionConfirmation
import net.jolabs40.tvslim.windows.ui.screens.ConnectionScreen
import net.jolabs40.tvslim.windows.ui.screens.ClosingDialog
import net.jolabs40.tvslim.windows.ui.screens.FilesScreen
import net.jolabs40.tvslim.windows.ui.screens.MemoryScreen
import net.jolabs40.tvslim.windows.ui.screens.PackagesScreen
import net.jolabs40.tvslim.windows.ui.screens.ScrcpyDownloadDialog
import net.jolabs40.tvslim.windows.ui.screens.RecordedVideoDialog
import net.jolabs40.tvslim.windows.ui.screens.UploadOverlay
import net.jolabs40.tvslim.windows.ui.theme.TvSlimTheme
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Renders logos and the cards that show them off screen, with fake data: no TV needed, and every case shows at
 * once (Startlight missing, installed, no third-party launcher; a Philips reporting itself as "TPV"; a box).
 * Opt-in:
 *
 *     ./gradlew jvmTest --tests "*LogoSheetTest*" -PlogoSheet=1 --rerun
 */
class LogoSheetTest {

    private val output = File(System.getProperty("tvslim.captures") ?: "build/captures")

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun `contact sheet of logos and the cards that show them`() {
        assumeTrue("-PlogoSheet=1 to render the sheet", System.getProperty("tvslim.logoSheet") != null)
        output.mkdirs()
        Locale.setDefault(Locale.FRANCE)
        val catalog = runBlocking { CatalogRepository { "fr" }.catalog() }

        fun state(home: String, vararg installed: String) = AppState(
            catalog = catalog,
            info = DeviceInfo(
                brand = "TCL",
                model = "Smart TV Pro",
                currentHome = home,
                thirdPartyLaunchers = installed.map { InstalledLauncher(packageName = it, name = it, component = "$it/.Home") },
            ),
        )

        render("10-accueil-recommandation", 720, 980) {
            HomeCard(state("com.spocky.projengmenu", "com.spocky.projengmenu", "com.example.launcher.unknown"), {}, {}, {})
        }
        render("11-accueil-startlight-installe", 720, 520) {
            HomeCard(state("net.jolabs40.startlight.debug", "net.jolabs40.startlight.debug", "me.efesser.flauncher"), {}, {}, {})
        }
        render("12-accueil-aucun-sombre", 720, 900, dark = true) {
            HomeCard(state("com.google.android.apps.tv.launcherx"), {}, {}, {})
        }
        render("13-logos-launchers", 960, 330) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LOGOS_LAUNCHERS.keys.forEach { id ->
                    Column(modifier = Modifier.width(120.dp)) {
                        LogoLauncher(id = id, size = 64.dp)
                        Text(
                            text = catalog.knownLaunchers.firstOrNull { it.id == id }?.name
                                ?: catalog.launchers.firstOrNull { it.id == id }?.name
                                ?: id,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        render("15-marques", 1080, 300) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Manufacturer.entries.forEach { BrandPlate(manufacturer = it, height = 44.dp) }
            }
        }
        render("16-marques-sombre", 1080, 240, dark = true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Manufacturer.entries.forEach { BrandPlate(manufacturer = it, height = 30.dp) }
            }
        }

        // A Philips reports "TPV" but the card must say Philips. A Shield is a box.
        render("17-appareil-philips", 720, 440) {
            DeviceCard(
                AppState(
                    info = DeviceInfo(
                        brand = "TPV", retailBrand = "Philips", model = "55PUS8807/12", androidVersion = "11",
                        totalMemoryMb = 2800, freeMemoryMb = 900, installedPackages = 180, disabledPackages = 12,
                        currentHome = "com.google.android.apps.tv.launcherx",
                    ),
                ),
                {}, {},
            )
        }
        render("18-appareil-shield-sombre", 720, 440, dark = true) {
            DeviceCard(
                AppState(
                    info = DeviceInfo(
                        brand = "NVIDIA", retailBrand = "NVIDIA", model = "SHIELD Android TV", androidVersion = "11",
                        totalMemoryMb = 2950, freeMemoryMb = 1400, installedPackages = 150, disabledPackages = 14,
                        currentHome = "com.spocky.projengmenu",
                    ),
                ),
                {}, {},
            )
        }

        // Discovered TVs: those connected to before show their brand.
        val discovery = AppState(
            catalog = catalog,
            discovery = DiscoveryResult(
                devices = listOf(
                    DiscoveredDevice(name = "tcl", host = "192.168.2.135", port = 5555),
                    DiscoveredDevice(name = "shieldtv", host = "192.168.2.193", port = 5555),
                    DiscoveredDevice(name = "192.168.2.40", host = "192.168.2.40", port = 5555),
                ),
                firstRoundDone = true,
            ),
            knownNames = mapOf("192.168.2.135" to "TCL Smart TV Pro", "192.168.2.193" to "NVIDIA SHIELD Android TV"),
        )
        render("19-decouverte-marques", 1280, 640, frame = false) {
            ConnectionScreen(
                discovery, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, PermissionsActions({}, {}, {}, {}, {}, {}, {}), ApplicationsState(),
                TvAppUiState(), TvAppActions({}, {}, {}), {},
                CommandActions({}, {}, {}),
            )
        }

        // A disabled stock launcher stays listed, and the current launcher is shown large.
        val launcherx = "com.google.android.apps.tv.launcherx"
        val disabledFactory = AppState(
            catalog = catalog,
            info = DeviceInfo(
                brand = "TCL",
                model = "Smart TV Pro",
                currentHome = "net.jolabs40.startlight.debug",
                thirdPartyLaunchers = listOf("com.spocky.projengmenu", "net.jolabs40.startlight.debug")
                    .map { InstalledLauncher(packageName = it, name = it, component = "$it/.Home") },
                factoryHomes = listOf(FactoryHome(launcherx, "$launcherx/.home.HomeActivity", active = false)),
            ),
        )
        render("20-accueil-usine-desactive", 720, 560) { HomeCard(disabledFactory, {}, {}, {}) }

        val factoryOnly = AppState(
            catalog = catalog,
            info = DeviceInfo(
                brand = "TCL",
                model = "Smart TV Pro",
                currentHome = launcherx,
                factoryHomes = listOf(FactoryHome(launcherx, "$launcherx/.home.HomeActivity", active = true)),
            ),
        )
        render("21-accueil-usine-seul", 720, 900) { HomeCard(factoryOnly, {}, {}, {}) }

        // What reapplying a configuration would change, shown before anything is touched.
        val plan = ReinjectionPlan(
            configuration = TvConfiguration(
                application = TvConfiguration.APPLICATION,
                format = TvConfiguration.FORMAT,
                savedAt = 1_789_300_000_000,
                device = SavedDevice(name = "TCL Smart TV Pro", androidVersion = "14"),
            ),
            toEnable = catalog.entries.filter { it.category == "streaming" }.take(1),
            toDisable = catalog.entries.filter { it.brand == "TCL" }.take(4),
            ignores = listOf("com.nvidia.stats", "com.nvidia.feedback"),
            home = HomeChange("com.spocky.projengmenu", "Projectivy Launcher", component = ""),
        )
        render("22-reinjection", 900, 760, frame = false) {
            ConfirmationDialog(Confirmation.Reinjection(plan), {}, {})
        }

        // Memory tab, switched to storage by clicking the second segment.
        val storage = AppState(
            catalog = catalog,
            connection = ConnectionUi(state = ConnectionState.CONNECTED, host = "192.168.2.135"),
            storageReadAttempted = true,
            storage = StorageBreakdown(
                totalKb = 51_170_024,
                freeKb = 45_111_156,
                applicationsBytes = 2_664_341_504,
                dataBytes = 1_880_899_072,
                cacheBytes = 961_830_912,
                photosBytes = 129_970_176,
                otherBytes = 764_686_336,
                applications = listOf(
                    ApplicationStorage("com.netflix.ninja", 152_000_000, 71_000_000, 43_000_000),
                    ApplicationStorage("com.google.android.apps.mediashell", 114_307_072, 376_832, 24_576),
                    ApplicationStorage("com.spocky.projengmenu", 48_000_000, 12_000_000, 3_000_000),
                    ApplicationStorage("com.tcl.esticker", 53_248, 221_184, 16_384),
                ),
            ),
        )
        // The selector is 360 wide starting at 20, so the second segment spans 200 to 380.
        render("23-stockage", 1280, 720, frame = false, click = Offset(290f, 36f)) {
            MemoryScreen(storage, {}, {}, {}, {})
        }

        // Packages tab with the profile list opened by a click; the field is at the top right.
        val lines = catalog.entries.mapIndexed { index, entry ->
            PackageRow(entry, if (index % 5 == 0) PackageState.DISABLED else PackageState.ACTIVE)
        }
        val packages = AppState(
            catalog = catalog,
            connection = ConnectionUi(state = ConnectionState.CONNECTED, host = "192.168.2.135"),
            lines = lines,
        )
        render("14-paquets-profils-ouverts", 1280, 860, frame = false, click = Offset(1080f, 110f)) {
            PackagesScreen(packages, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

        // One catalogue row per origin, then the packages the catalogue does not know, grouped by publisher.
        val unknowns = AppState(
            catalog = catalog,
            connection = ConnectionUi(state = ConnectionState.CONNECTED, host = "192.168.2.135"),
            lines = PackageOrigin.ORDER.mapNotNull { origin -> lines.firstOrNull { it.entry.origin == origin } },
            unknowns = catalog.unknownPackages(
                system = mapOf(
                    "com.tcl.guard" to PackageState.ACTIVE,
                    "com.tcl.tvinput" to PackageState.ACTIVE,
                    "com.tcl.inputmethod.international" to PackageState.ACTIVE,
                    "com.mediatek.android.tv.mdns.offload.overlay" to PackageState.ACTIVE,
                    "com.mediatek.AirplayAPK" to PackageState.DISABLED,
                    "com.google.android.tv.remote.service" to PackageState.ACTIVE,
                    "com.android.se" to PackageState.ACTIVE,
                    "com.dolby.android.audio.service" to PackageState.ACTIVE,
                ),
                manufacturer = Manufacturer.TCL,
            ),
        )
        render("24-paquets-inconnus", 1280, 860, frame = false) {
            PackagesScreen(unknowns, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

        // APK install: the TV tab while connected, an upload in progress, both outcomes, the downgrade
        // confirmation, and the overlay shown while a file is dragged over the window.
        val hippie = ChosenApk(
            file = File("HippieTV-2.4.0.apk"),
            name = "HippieTV-2.4.0.apk",
            size = 48_300_000,
            manifest = ApkManifest("net.jolabs40.hippietv", versionCode = 240, versionName = "2.4.0", minSdk = 26),
            installed = InstalledVersion(251, "2.5.1"),
        )
        val reached = AppState(
            catalog = catalog,
            connection = ConnectionUi(state = ConnectionState.CONNECTED, host = "192.168.2.135"),
            info = DeviceInfo(brand = "TCL", model = "Smart TV Pro", androidVersion = "14"),
            installation = InstallationState(phase = InstallationPhase.Upload(21_700_000, 48_300_000)),
        )
        render("25-televiseur-installation", 1280, 1100, frame = false) {
            ConnectionScreen(
                reached, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, PermissionsActions({}, {}, {}, {}, {}, {}, {}), ApplicationsState(),
                // TV app card: not installed on the TV, 1.1.0 released.
                TvAppUiState(
                    situation = TvSituation(
                        available = TvRelease("1.1.0", 10100, "TVSlim-TV-1.1.0.apk", "https://github.com/", 1_300_000),
                    ),
                ),
                TvAppActions({}, {}, {}), {},
                CommandActions({}, {}, {}),
            )
        }
        render("26-installation-bilans", 720, 620) {
            InstallationCard(InstallationState(last = InstallationResult.Succeeded(hippie))) {}
            Spacer(Modifier.height(16.dp))
            InstallationCard(
                InstallationState(
                    last = InstallationResult.Failed(
                        apk = hippie,
                        cause = FailureCause.SIGNATURE_MISMATCH,
                        detail = "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package net.jolabs40.hippietv " +
                            "signatures do not match newer version; ignoring!]",
                    ),
                ),
            ) {}
        }
        render("27-confirmation-installation", 900, 520, frame = false) {
            ConfirmationDialog(Confirmation.Installation(hippie), {}, {})
        }
        render("28-depot-apk", 900, 520, frame = false) {
            UploadOverlay(connected = true, tvName = "TCL Smart TV Pro")
        }

        // Free-form command: a normal output, then a command that never ends and is cut by the timeout.
        render("29-commande-adb", 720, 1040) {
            CommandCard(
                CommandState(
                    input = "pm list packages -d",
                    last = CommandExchange(
                        command = "pm list packages -d",
                        code = 0,
                        output = listOf("com.tcl.gallery", "com.tcl.esticker", "com.google.android.apps.tv.launcherx")
                            .joinToString("\n") { "package:$it" },
                    ),
                ),
                CommandActions({}, {}, {}),
            )
            Spacer(Modifier.height(16.dp))
            CommandCard(
                CommandState(
                    input = "logcat",
                    last = CommandExchange(
                        command = "logcat",
                        code = null,
                        output = "09-14 08:31:02.114  1532  1532 I ActivityManager: Start proc 4121:com.tcl.tvweishi",
                        interruption = Interruption.TIMEOUT,
                        reason = "délai dépassé",
                    ),
                ),
                CommandActions({}, {}, {}),
            )
        }

        // Files tab: a folder listing, an upload in progress, its confirmation, a denied folder.
        val day = 1_790_000_000_000L
        val movies = ExplorerState(
            path = "/sdcard/Movies",
            reading = FolderRead.Read(
                "/sdcard/Movies",
                listOf(
                    RemoteEntry("Séries", EntryKind.FOLDER, 4096, day),
                    RemoteEntry("Vacances 2024", EntryKind.FOLDER, 4096, day - 86_400_000L),
                    RemoteEntry(".thumbnails", EntryKind.FOLDER, 4096, day),
                    RemoteEntry("Le Grand Bleu (1988).mkv", EntryKind.FILE, 4_381_220_112, day),
                    RemoteEntry("bande-annonce.mp4", EntryKind.FILE, 48_300_000, day),
                    RemoteEntry("sous-titres.srt", EntryKind.FILE, 91_204, day),
                    RemoteEntry("dernier", EntryKind.FILE, 17, day, link = true),
                ),
            ),
            shortcuts = Shortcut.withVolumes(listOf("1A2B-3C4D")),
        )
        val actions = FilesActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        render("30-fichiers", 1280, 760, frame = false) {
            FilesScreen(connected = true, state = movies, actions = actions)
        }
        render("31-fichiers-envoi-sombre", 1280, 520, dark = true, frame = false) {
            FilesScreen(
                connected = true,
                state = movies.copy(
                    progress = UploadProgress("Vacances 2024/plage.jpg", 37, 212, 1_204_000_000, 2_910_000_000),
                ),
                actions = actions,
            )
        }
        val batch = LocalBatch(
            files = listOf("Vacances 2024/plage.jpg", "Vacances 2024/dune.jpg", "Le Grand Bleu (1988).mkv")
                .map { path ->
                    object : LocalFile {
                        override val path = path
                        override val size = 2_000_000_000L
                        override val date = 0L
                        override fun open() = ByteArrayInputStream(ByteArray(0))
                    }
                },
            folders = listOf("Vacances 2024", "Vacances 2024/vide"),
        )
        render("32-fichiers-confirmation", 900, 520, frame = false) {
            UploadConfirmation(
                UploadPlan("/sdcard/Movies", batch, existing = listOf("Le Grand Bleu (1988).mkv", "Vacances 2024")),
                {},
                {},
            )
        }
        render("33-fichiers-refus", 1280, 560, frame = false) {
            FilesScreen(
                connected = true,
                state = ExplorerState(
                    path = "/data",
                    reading = FolderRead.Rejected("/data"),
                    last = UploadResult(
                        destination = "/system",
                        sentCount = 0,
                        count = 2,
                        failures = listOf(
                            UploadFailure("a.txt", "couldn't create file: Read-only file system"),
                            UploadFailure("b.txt", "couldn't create file: Read-only file system"),
                        ),
                    ),
                ),
                actions = actions,
            )
        }
        render("34-depot-fichiers", 900, 520, frame = false) {
            UploadOverlay(connected = true, tvName = "TCL Smart TV Pro", destination = "/sdcard/Movies")
        }

        // Copy to PC and delete: on hover, on right-click, their confirmations, a copy in progress.
        render("35-fichiers-survol", 1280, 520, frame = false, hover = Offset(600f, 330f)) {
            FilesScreen(connected = true, state = movies, actions = actions)
        }
        render("36-fichiers-clic-droit", 1280, 520, frame = false, rightClick = Offset(600f, 344f)) {
            FilesScreen(connected = true, state = movies, actions = actions)
        }
        render("37-fichiers-copie-sombre", 1280, 520, dark = true, frame = false) {
            FilesScreen(
                connected = true,
                state = movies.copy(
                    progress = UploadProgress("Vacances 2024/plage.jpg", 12, 212, 404_000_000, 2_910_000_000, TransferDirection.DOWNLOAD),
                ),
                actions = actions,
            )
        }
        val downloads = object : LocalTarget {
            override fun describe(path: String) =
                "C:\\Users\\Camille\\Downloads" + path.split('/').filter { it.isNotEmpty() }.joinToString("") { "\\$it" }
            override fun exists(path: String) = true
            override fun createFolder(path: String) = Unit
            override fun write(path: String) = error("nothing is written")
        }
        render("38-fichiers-copie-confirmation", 900, 520, frame = false) {
            DownloadConfirmation(
                DownloadPlan(
                    source = "/sdcard/Movies/Vacances 2024",
                    target = downloads,
                    name = "Vacances 2024",
                    folder = true,
                    files = List(212) { RemoteFile("/sdcard/Movies/Vacances 2024/$it.jpg", "Vacances 2024/$it.jpg", 13_726_000, 0L) },
                    folders = listOf("Vacances 2024", "Vacances 2024/vide"),
                    alreadyExists = true,
                ),
                {},
                {},
            )
        }
        render("39-fichiers-suppression-confirmation", 900, 520, frame = false) {
            DeletionConfirmation(
                DeletionPlan("/sdcard/Movies/Vacances 2024", DeletionKind.FOLDER, 212, 4, 2_910_000_000),
                {},
                {},
            )
        }
        render("40-fichiers-derniere-copie", 1280, 600, frame = false) {
            FilesScreen(
                connected = true,
                state = movies.copy(
                    last = UploadResult(
                        destination = "C:\\Users\\Camille\\Downloads\\Vacances 2024",
                        sentCount = 211,
                        count = 212,
                        failures = listOf(UploadFailure("Vacances 2024/dune.jpg", "open failed: Permission denied")),
                        direction = TransferDirection.DOWNLOAD,
                    ),
                ),
                actions = actions,
            )
        }

        // The support banner at the top of the window, and its permanent link in the About dialog.
        render("41-soutien", 1280, 80, frame = false) { SupportBanner(true, {}, {}, {}) }
        render("42-soutien-sombre", 1280, 80, dark = true, frame = false) { SupportBanner(true, {}, {}, {}) }
        render("43-a-propos", 900, 760, frame = false) {
            AboutDialog(UpdateState(currentVersion = "1.4.0"), {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

        // TV screen: the top bar buttons idle, then during a mirror, a recording and a download; the screenshot
        // preview, the scrcpy download offer, the recorded video.
        render("44-ecran-boutons", 640, 300) {
            val now = System.currentTimeMillis()
            ScreenActions(ScreenState(), connected = true, {}, {}, {}, {}, {})
            ScreenActions(ScreenState(scrcpy = ScrcpyPhase.Active), connected = true, {}, {}, {}, {}, {})
            ScreenActions(ScreenState(recording = RecordingPhase.InProgress(now - 83_000, null)), connected = true, {}, {}, {}, {}, {})
            ScreenActions(ScreenState(recording = RecordingPhase.InProgress(now - 42_000, 180)), connected = true, {}, {}, {}, {}, {})
            ScreenActions(
                ScreenState(captureInProgress = true, scrcpy = ScrcpyPhase.Downloading(0.4f), recording = RecordingPhase.Copy(0.7f)),
                connected = true, {}, {}, {}, {}, {},
            )
        }
        val png = ByteArrayOutputStream().also { stream ->
            val image = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.paint = GradientPaint(0f, 0f, java.awt.Color(0x1F4A6E), 1920f, 1080f, java.awt.Color(0x9CF2C9))
            graphics.fillRect(0, 0, 1920, 1080)
            graphics.dispose()
            ImageIO.write(image, "png", stream)
        }.toByteArray()
        val picturesFolder = File("C:/Users/Camille/Pictures/TV Slim")
        render("45-apercu-capture", 1000, 760, frame = false) {
            CapturePreviewDialog(
                CompletedCapture(File(picturesFolder, "TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-15-30.png"), png, 1920, 1080),
                {},
                {},
                {},
            )
        }
        render("46-telechargement-scrcpy", 900, 560, frame = false) {
            ScrcpyDownloadDialog(ScrcpyPhase.Downloading(0.62f), File("C:/Users/Camille/AppData/Local/TVSlim/scrcpy"), {}, {})
        }
        render("47-video-enregistree", 900, 560, dark = true, frame = false) {
            RecordedVideoDialog(File("C:/Users/Camille/Videos/TV Slim/TVSlim-TCL-Smart-TV-Pro-2026-10-04_19-20-02.mp4"), {}, {})
        }
        render("48-fermeture-video", 900, 480, frame = false) { ClosingDialog(RecordingPhase.Copy(0.55f)) }
        // A connected phone: only the current launcher, no recommendation or stock launchers (data from a Pixel 9a).
        val pixel = AppState(
            catalog = catalog,
            info = DeviceInfo(
                brand = "Google",
                retailBrand = "google",
                model = "Pixel 9a",
                currentHome = "com.teslacoilsw.launcher",
                thirdPartyLaunchers = listOf("net.jolabs40.startlight.debug", "com.teslacoilsw.launcher")
                    .map { InstalledLauncher(packageName = it, name = it, component = "$it/.Home") },
                characteristics = "nosdcard",
                features = setOf(DeviceInfo.FEATURE_TOUCHSCREEN),
            ),
        )
        render("49-accueil-telephone", 720, 300) { HomeCard(pixel, {}, {}, {}) }

        // Permissions: the app picked from the list, and what it declares.
        val loadedPermissions = PermissionsState(
            packageName = "net.jolabs40.tvslim",
            fetched = PackagePermissions(
                packageFound = true,
                requested = setOf(
                    "android.permission.WRITE_SECURE_SETTINGS",
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.RECEIVE_BOOT_COMPLETED",
                    "android.permission.QUERY_ALL_PACKAGES",
                ),
                granted = setOf("android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.QUERY_ALL_PACKAGES"),
            ),
            loadedPackage = "net.jolabs40.tvslim",
        )
        render("50-permissions-declarees", 620, 640) {
            PermissionsCard(loadedPermissions, ApplicationsState(), PermissionsActions({}, {}, {}, {}, {}, {}, {}))
        }

        // The Ko-fi icon in the top bar, light and dark.
        render("50-bouton-soutien", 120, 80) { SupportButton {} }
        render("51-bouton-soutien-sombre", 120, 80, dark = true) { SupportButton {} }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        frame: Boolean = true,
        click: Offset? = null,
        hover: Offset? = null,
        rightClick: Offset? = null,
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                TvSlimTheme(dark = dark) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        if (frame) {
                            Column(modifier = Modifier.padding(16.dp)) { content() }
                        } else {
                            content()
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
            if (click != null) {
                scene.sendPointerEvent(PointerEventType.Press, click)
                scene.sendPointerEvent(PointerEventType.Release, click)
                repeat(4) { i ->
                    scene.render(1_000_000_000L + i * 100_000_000L)
                    Thread.sleep(150)
                }
            }
            if (hover != null) {
                scene.sendPointerEvent(PointerEventType.Enter, hover)
                scene.sendPointerEvent(PointerEventType.Move, hover)
                repeat(4) { i ->
                    scene.render(1_000_000_000L + i * 100_000_000L)
                    Thread.sleep(150)
                }
            }
            if (rightClick != null) {
                val rightButton = PointerButtons(isSecondaryPressed = true)
                scene.sendPointerEvent(PointerEventType.Move, rightClick)
                scene.sendPointerEvent(PointerEventType.Press, rightClick, buttons = rightButton, button = PointerButton.Secondary)
                scene.sendPointerEvent(PointerEventType.Release, rightClick, button = PointerButton.Secondary)
                repeat(4) { i ->
                    scene.render(1_000_000_000L + i * 100_000_000L)
                    Thread.sleep(150)
                }
            }
            val image = scene.render(3_000_000_000L)
            File(output, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close()
        }
    }
}
