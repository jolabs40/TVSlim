package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.configuration.ConfigurationFile
import net.jolabs40.tvslim.configuration.configurationOf
import net.jolabs40.tvslim.configuration.buildPlan
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.files.FolderRead
import net.jolabs40.tvslim.screen.TvRecording
import net.jolabs40.tvslim.windows.Locations
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.AdbKeyStore
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.screen.ScrcpyInstallation
import net.jolabs40.tvslim.windows.screen.ScrcpyLocator
import net.jolabs40.tvslim.windows.update.GithubClient
import net.jolabs40.tvslim.windows.update.Distribution
import net.jolabs40.tvslim.windows.update.UpdateInstaller
import net.jolabs40.tvslim.windows.update.DistributionMode
import net.jolabs40.tvslim.windows.update.UpdatesController
import net.jolabs40.tvslim.windows.update.Version
import net.jolabs40.tvslim.windows.network.TvDiscovery
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.msg_unknown_export_failed
import net.jolabs40.tvslim.windows.resources.msg_unknown_exported
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
 * Uses the app's ADB key (`%APPDATA%\TVSlim\keys`), so an authorization accepted on the TV during the test also
 * holds for the app. Journal, measurements and preferences live in a temporary folder. No write command is sent.
 */
class CapturesHardwareTest {

    private val host: String? = System.getProperty("tvslim.hardware")
    private val output = File(System.getProperty("tvslim.captures") ?: "build/captures")

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * Runs [block] on the AWT thread (`Dispatchers.Main`). The registry above has no lock: composition fed it from
     * the test thread while `collectAsStateWithLifecycle` touched it from AWT, which eventually threw an
     * `ArrayIndexOutOfBoundsException`.
     */
    private fun <T> onAwtThread(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun `the four tabs with a real TV`() {
        assumeTrue("-Pmateriel=<adresse> pour capturer sur un vrai téléviseur", host != null)
        val address = host!!
        output.mkdirs()
        Locale.setDefault(Locale.FRANCE)

        val tempDir = Files.createTempDirectory("tvslim-captures").toFile()
        val locations = Locations(File(tempDir, "donnees"), File(tempDir, "local"))
        val client = AdbClient(AdbKeyStore(Locations.windows().keys))
        val preferences = WindowsPreferences(locations.preferences)
        val github = GithubClient(repository = "jolabs40/TVSlim", appVersion = "captures")
        val controller = AppController(client, CatalogRepository(), preferences, TvDiscovery(), locations)
        val updates = UpdatesController(
            preferences = preferences,
            client = github,
            installer = UpdateInstaller(locations.downloads, github, ""),
            distribution = Distribution(DistributionMode.DEVELOPMENT, null),
            currentVersion = Version(1, 0, 0),
            repository = "jolabs40/TVSlim",
            quit = {},
            openLink = {},
        )
        val screen = ScreenController(
            reader = client,
            recording = TvRecording(client, client),
            locator = ScrcpyLocator(locations.scrcpy),
            installation = ScrcpyInstallation(github, locations.scrcpy),
            target = { null },
            picturesFolder = { tempDir },
            videosFolder = { tempDir },
        )

        fun takeCapture(name: String, tab: AppTab, dark: Boolean = false, waitMs: Long = 1_500, ready: () -> Boolean = { true }) {
            // Create, render and close on the AWT thread; waits stay on the test thread so AWT can run the
            // controller's coroutines meanwhile.
            val scene = onAwtThread {
                ImageComposeScene(width = 1280, height = 860, density = Density(1f)) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                        TvSlimTheme(dark = dark) {
                            AppWindow(
                                controller = controller,
                                updates = updates,
                                screen = screen,
                                scrcpyFolder = locations.scrcpy,
                                tab = tab,
                                onTabSelected = {},
                                openLink = {},
                                openDataFolder = {},
                                chooseExportFile = { _, _ -> null },
                                chooseImportFile = { null },
                                chooseApk = { null },
                                chooseFiles = { emptyList() },
                                chooseFolder = { null },
                                chooseFileDestination = { _, _ -> null },
                                chooseFolderDestination = { null },
                                openFolder = {},
                            )
                        }
                    }
                }
            }
            try {
                onAwtThread { scene.render(0) }
                val limit = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < limit && !ready()) Thread.sleep(200)
                repeat(3) { i ->
                    Thread.sleep(250)
                    onAwtThread { scene.render((i + 1) * 1_000_000_000L) }
                }
                val image = onAwtThread { scene.render(5_000_000_000L) }
                File(output, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally {
                onAwtThread { scene.close() }
            }
        }

        fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val limit = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < limit) {
                if (condition()) return true
                Thread.sleep(250)
            }
            return condition()
        }

        // 1. Before connecting: network discovery, for one full scan round.
        takeCapture("01-recherche", AppTab.TV, waitMs = 15_000) {
            controller.state.value.discovery.firstRoundDone
        }

        // 2. Connect. The first time, the TV asks for authorization, to be accepted with the remote;
        //    keeps retrying until the timeout.
        controller.updateHost(address)
        controller.updatePort("5555")
        val connected = waitFor(240_000) {
            val state = controller.state.value
            if (state.connection.state == ConnectionState.ERROR || state.connection.state == ConnectionState.DISCONNECTED) {
                controller.connect()
                Thread.sleep(1_000)
            }
            state.connected && state.lines.isNotEmpty()
        }
        assertTrue("Connexion impossible : ${controller.state.value.connection}", connected)

        takeCapture("02-televiseur", AppTab.TV)

        val firstDisabled = controller.state.value.lines.firstOrNull { it.state == PackageState.DISABLED }
            ?: controller.state.value.lines.first { it.state != PackageState.ABSENT }
        controller.showDetails(firstDisabled.entry.packageName)
        takeCapture("03-paquets", AppTab.PACKAGES)
        takeCapture("04-paquets-sombre", AppTab.PACKAGES, dark = true)

        // Memory: the tab starts reading on first open. Record what happens, to tell a slow read from a
        // failed or never-started one.
        val readings = mutableListOf<String>()
        val start = System.currentTimeMillis()
        var loadingSeen = false
        takeCapture("05-memoire", AppTab.MEMORY, waitMs = 40_000) {
            val state = controller.state.value
            loadingSeen = loadingSeen || state.loading
            state.memoryReadAttempted
        }
        val after = controller.state.value
        readings += "memoire: ${System.currentTimeMillis() - start} ms, chargementVu=$loadingSeen, " +
            "tentee=${after.memoryReadAttempted}, totalKo=${after.memory.totalKb}, " +
            "processus=${after.memory.processes.size}, connecte=${after.connected}"
        if (!after.memoryReadAttempted) {
            controller.refreshMemory()
            val loaded = waitFor(40_000) { controller.state.value.memoryReadAttempted }
            readings += "lecture explicite: aboutie=$loaded, totalKo=${controller.state.value.memory.totalKb}"
            takeCapture("05b-memoire-explicite", AppTab.MEMORY)
        }
        takeCapture("06-journal", AppTab.JOURNAL)

        // Files tab: internal storage, read as on a first visit, and the mounted volumes.
        controller.files.explorer.start()
        takeCapture("09-fichiers", AppTab.FILES, waitMs = 20_000) {
            controller.files.explorer.state.value.reading is FolderRead.Read
        }
        readings += "fichiers: ${controller.files.explorer.state.value.reading?.javaClass?.simpleName}, " +
            "entrees=${controller.files.explorer.state.value.entries.size}, " +
            "raccourcis=${controller.files.explorer.state.value.shortcuts.map { it.path }}"

        // Applications tab: the helper is copied to /data/local/tmp, run, then deleted; names and icons are read.
        // The test's cache is empty, so everything is read (about 30 s on a phone).
        controller.applications.load()
        takeCapture("10-applications", AppTab.APPLICATIONS, waitMs = 120_000) { controller.applications.state.value.loaded }
        readings += "applications: ${controller.applications.state.value.applications.size}, " +
            "avec icone=${controller.applications.state.value.applications.count { it.loaded }}"

        // A saved configuration, read back against the TV it describes, must have nothing to reapply.
        // Computed in memory; no command is sent to the TV.
        val justRead = controller.state.value
        val readStates = justRead.lines.associate { it.entry.packageName to it.state }
        val backup = justRead.catalog.configurationOf(justRead.info, readStates)
        val plan = ConfigurationFile.read(ConfigurationFile.write(backup))
            ?.buildPlan(justRead.catalog, readStates, justRead.info)
        readings += "configuration: desactives=${backup.disabled.size}, actifs=${backup.active.size}, " +
            "accueil=${backup.home?.packageName}, actions=${plan?.actionCount}, accueilAChanger=${plan?.home}"
        readings += "accueilsUsine=${justRead.info.factoryHomes}"
        assertTrue("Une configuration relue doit correspondre au téléviseur : $plan", plan != null && plan.nothingToDo)
        assertTrue("L'accueil en place ne doit pas être à rétablir : ${plan?.home}", plan?.home == null)

        // Storage, read the way the tab does.
        controller.refreshStorage()
        val storageRead = waitFor(40_000) { controller.state.value.storageReadAttempted }
        val storage = controller.state.value.storage
        readings += "stockage: lu=$storageRead, totalKo=${storage.totalKb}, libreKo=${storage.freeKb}, " +
            "applications=${storage.applications.size}"
        assertTrue("Le stockage du téléviseur doit se lire", storage.populated)

        // Unknown-package inventory, read and written as the export button does, next to the screenshots. No
        // window is open at this point, so the completion message stays and reports the outcome.
        val inventory = File(output, "inconnus.md").apply { delete() }
        controller.configuration.exportUnknowns(inventory)
        val finalMessages = setOf(Res.string.msg_unknown_exported, Res.string.msg_unknown_export_failed)
        val finished = waitFor(60_000) { (controller.state.value.message as? UiMessage.Localized)?.resource in finalMessages }
        val report = inventory.takeIf { it.isFile }?.readText().orEmpty()
        readings += "inconnus: ${justRead.unknowns.size}, termine=$finished, message=${controller.state.value.message}"
        readings += report.lineSequence()
            .filter { it.startsWith("- Lu sur") || it.startsWith("- Avec les droits") || it.startsWith("- Firmware") || it.startsWith("- Déjà au") }
            .joinToString(" / ")
        assertTrue("L'inventaire doit s'écrire : ${controller.state.value.message}", report.isNotEmpty())
        assertTrue(
            "Les quatre lectures doivent aboutir : ${report.take(800)}",
            report.contains("- Lu sur l'appareil : indices ADB, mémoire vive, stockage, firmware\n"),
        )
        assertTrue("Le catalogue connaît des paquets de la TCL : ${report.take(800)}", report.contains("## Déjà au catalogue ("))

        // Brings the unknown-packages section to the top by searching for the first family name.
        justRead.unknowns.firstOrNull()?.let { first ->
            controller.updateSearch(first.family)
            takeCapture("08-paquets-inconnus", AppTab.PACKAGES)
            controller.updateSearch("")
        }

        Locale.setDefault(Locale.ENGLISH)
        takeCapture("07-paquets-anglais", AppTab.PACKAGES)

        controller.disconnect()
        File(output, "resume.txt").writeText(
            buildString {
                val state = controller.state.value
                appendLine("hote=$address")
                appendLine("detectes=${state.detected.joinToString { "${it.label}@${it.host}:${it.port}" }}")
                readings.forEach { appendLine(it) }
            },
        )
    }
}
