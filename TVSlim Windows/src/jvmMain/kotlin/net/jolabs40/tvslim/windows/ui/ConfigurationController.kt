package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.command.AdbConsole
import net.jolabs40.tvslim.command.CommandRejection
import net.jolabs40.tvslim.command.CommandInput
import net.jolabs40.tvslim.configuration.ConfigurationFile
import net.jolabs40.tvslim.configuration.ReinjectionPlan
import net.jolabs40.tvslim.configuration.Reinjector
import net.jolabs40.tvslim.configuration.configurationOf
import net.jolabs40.tvslim.configuration.buildPlan
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.device.CatalogSuggestion
import net.jolabs40.tvslim.device.UnknownsReport
import net.jolabs40.tvslim.device.UnknownsSurvey
import net.jolabs40.tvslim.installation.ChosenApk
import net.jolabs40.tvslim.installation.ApkReview
import net.jolabs40.tvslim.installation.ApkInstallation
import net.jolabs40.tvslim.installation.ApkRejection
import net.jolabs40.tvslim.installation.InstallationResult
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.support.SupportInvitation
import net.jolabs40.tvslim.windows.AppInfo
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.msg_apk_bundle
import net.jolabs40.tvslim.windows.resources.msg_apk_busy
import net.jolabs40.tvslim.windows.resources.msg_apk_failed
import net.jolabs40.tvslim.windows.resources.msg_apk_installed
import net.jolabs40.tvslim.windows.resources.msg_apk_invalid_package
import net.jolabs40.tvslim.windows.resources.msg_apk_not_apk
import net.jolabs40.tvslim.windows.resources.msg_apk_sdk
import net.jolabs40.tvslim.windows.resources.msg_apk_unreachable
import net.jolabs40.tvslim.windows.resources.msg_command_empty
import net.jolabs40.tvslim.windows.resources.msg_command_not_shell
import net.jolabs40.tvslim.windows.resources.msg_command_too_long
import net.jolabs40.tvslim.windows.resources.msg_config_home_missing
import net.jolabs40.tvslim.windows.resources.msg_config_invalid
import net.jolabs40.tvslim.windows.resources.msg_config_read_failed
import net.jolabs40.tvslim.windows.resources.msg_config_save_failed
import net.jolabs40.tvslim.windows.resources.msg_config_saved
import net.jolabs40.tvslim.windows.resources.msg_config_up_to_date
import net.jolabs40.tvslim.windows.resources.msg_connect_first
import net.jolabs40.tvslim.windows.resources.msg_home_failed
import net.jolabs40.tvslim.windows.resources.msg_home_kept
import net.jolabs40.tvslim.windows.resources.msg_home_set
import net.jolabs40.tvslim.windows.resources.msg_launcher_installed
import net.jolabs40.tvslim.windows.resources.msg_store_failed
import net.jolabs40.tvslim.windows.resources.msg_store_opened
import net.jolabs40.tvslim.windows.resources.msg_unknown_export_failed
import net.jolabs40.tvslim.windows.resources.msg_unknown_exported
import net.jolabs40.tvslim.windows.resources.msg_unknown_proposed
import net.jolabs40.tvslim.windows.resources.msg_unknown_reading
import java.io.File

/**
 * TV configuration: home screen (recommended launcher, install watch), saved configurations to reapply later
 * (launcher and packages), the unknown packages report, APK install and the free ADB command.
 *
 * Split from the main controller like permissions: it shares only state and engine, and reapplying goes
 * through the same safeguards as a batch disable.
 */
class ConfigurationController(
    private val reader: RemoteReader,
    private val engine: () -> DebloatEngine?,
    private val installation: () -> ApkInstallation?,
    private val console: AdbConsole,
    private val state: () -> AppState,
    private val updateState: ((AppState) -> AppState) -> Unit,
    private val scope: CoroutineScope,
    private val show: (UiMessage) -> Unit,
    private val refresh: () -> Unit,
    private val finish: (List<ActionResult>) -> Unit,
    /** A reapply or install succeeded: the support banner may show. */
    private val thank: () -> Unit,
) {

    /** Watches for a launcher whose install was just started. */
    private var watchJob: Job? = null

    /** Called on disconnect: the install watch and result only applied to the previous TV. */
    fun forget() {
        watchJob?.cancel()
        watchJob = null
        updateInstallation { InstallationState() }
        // Typed commands stay within reach of Up; the output belonged to the previous TV.
        updateCommand { CommandState(input = it.input, history = it.history) }
    }

    // --- Home screen ----------------------------------------------------------------------

    /**
     * Opens a launcher's page in the TV's store. The user confirms the install with the remote; the store is
     * the recommended path even where an APK exists.
     */
    fun installLauncher(packageName: String) {
        // The card offers nothing on non-TV devices; do nothing either if called some other way.
        if (!state().info.deviceType.forCatalog) return
        val activeEngine = engine() ?: return show(text(Res.string.msg_connect_first))
        scope.launch {
            val result = activeEngine.openStoreListing(packageName)
            if (!result.succeeded) {
                show(text(Res.string.msg_store_failed, result.text()))
                return@launch
            }
            show(text(Res.string.msg_store_opened))
            watchInstallation(packageName)
        }
    }

    /**
     * Makes an installed launcher the TV's home screen. Logged, so it can be undone from the Log tab. Re-read
     * afterwards: Android answers `Success` without changing anything while a higher-priority factory home is
     * still enabled (Google TV on the TCL).
     */
    fun setHome(component: String) {
        // The card offers nothing on non-TV devices; do nothing either if called some other way.
        if (!state().info.deviceType.forCatalog) return
        val activeEngine = engine() ?: return show(text(Res.string.msg_connect_first))
        val info = state().info
        val packageName = component.substringBefore('/')
        val name = state().catalog.launcherName(packageName) ?: packageName
        scope.launch {
            val result = activeEngine.setHome(component, info.homeComponent.ifBlank { component })
            if (!result.succeeded) {
                show(text(Res.string.msg_home_failed, if (result.reason == null && result.message.isBlank()) UiMessage.Raw("—") else result.text()))
                return@launch
            }
            val inPlace = reader.currentHome()
            refresh()
            show(
                if (inPlace == packageName) {
                    text(Res.string.msg_home_set, name)
                } else {
                    text(Res.string.msg_home_kept, state().catalog.launcherName(inPlace) ?: inPlace)
                },
            )
        }
    }

    /**
     * Polls for the launcher instead of requiring a Refresh, since the user is at the TV, not the PC. One short
     * query every five seconds, for three minutes.
     */
    private fun watchInstallation(packageName: String) {
        watchJob?.cancel()
        watchJob = scope.launch {
            withTimeoutOrNull(WATCH_DURATION_MS) {
                while (isActive) {
                    delay(WATCH_INTERVAL_MS)
                    if (!state().connected) return@withTimeoutOrNull
                    if (reader.isInstalled(packageName)) {
                        refresh()
                        show(text(Res.string.msg_launcher_installed))
                        return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    // --- Save and reapply -----------------------------------------------------------------

    /** Name suggested by the save dialog: device and date. */
    fun fileName(): String = ConfigurationFile.suggestedName(state().info)

    /** Writes the TV configuration as last read. */
    fun save(target: File) {
        val current = state()
        if (!current.connected || current.lines.isEmpty()) return show(text(Res.string.msg_connect_first))
        val configuration = current.catalog.configurationOf(current.info, current.states())
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { target.writeText(ConfigurationFile.write(configuration)) } }
                .onSuccess { show(text(Res.string.msg_config_saved, target.path)) }
                .onFailure { show(text(Res.string.msg_config_save_failed, it.message.orEmpty())) }
        }
    }

    /**
     * Reads a saved configuration and compares it with the TV. Nothing is sent: changes go to confirmation, and
     * a TV that already matches is reported without a dialog.
     */
    fun load(source: File) {
        if (!state().connected || engine() == null) return show(text(Res.string.msg_connect_first))
        scope.launch {
            val loaded = runCatching { withContext(Dispatchers.IO) { source.readText() } }
            val configuration = loaded.getOrNull()?.let(ConfigurationFile::read)
            val current = state()
            when {
                loaded.isFailure ->
                    show(text(Res.string.msg_config_read_failed, loaded.exceptionOrNull()?.message.orEmpty()))

                configuration == null -> show(text(Res.string.msg_config_invalid))
                else -> suggest(configuration.buildPlan(current.catalog, current.states(), current.info))
            }
        }
    }

    private fun suggest(plan: ReinjectionPlan) {
        val missingHome = plan.home
        when {
            !plan.nothingToDo -> updateState { it.copy(confirmation = Confirmation.Reinjection(plan)) }
            missingHome != null -> show(text(Res.string.msg_config_home_missing, missingHome.name))
            else -> show(text(Res.string.msg_config_up_to_date))
        }
    }

    /**
     * Offers to restore what drifted. Same confirmation, [Reinjector] and safeguards as a reapply; only the
     * plan's source differs.
     */
    fun suggestDriftRevert() {
        val plan = state().drift ?: return
        updateState { it.copy(confirmation = Confirmation.Reinjection(plan, drift = true)) }
    }

    /** Reapplies after confirmation, with the progress and summary of a batch. */
    fun reinject(plan: ReinjectionPlan) {
        val current = state()
        val activeEngine = engine() ?: return show(text(Res.string.msg_connect_first))
        scope.launch {
            updateState { it.copy(progress = BatchProgress(0, plan.actionCount)) }
            val results = Reinjector(activeEngine).reinject(
                plan = plan,
                catalog = current.catalog,
                states = current.states(),
                info = current.info,
                onProgress = { done, total -> updateState { it.copy(progress = BatchProgress(done, total)) } },
            )
            finish(results)
            if (SupportInvitation.deserves(results)) thank()
        }
    }

    // --- Unknown packages report ----------------------------------------------------------

    /** Suggested report name: device and date. */
    fun unknownsExportName(): String = UnknownsReport.suggestedName(state().info)

    /**
     * Writes a report of packages missing from the catalogue, with what ADB says about each (location, flags,
     * sensitive declarations, icon, RAM and storage), then the firmware and the catalogue entries the device
     * has. Everything is re-read at export time in four reads; nothing is written to the TV.
     *
     * With [thenOpen], the catalogue form then opens in the browser for the user to attach the file and send it.
     */
    fun exportUnknowns(target: File, thenOpen: ((String) -> Unit)? = null) {
        if (!state().connected) return show(text(Res.string.msg_connect_first))
        scope.launch {
            show(text(Res.string.msg_unknown_reading))
            runCatching {
                val survey = UnknownsSurvey(
                    hints = reader.hints(),
                    memory = reader.memory(),
                    storage = reader.storage(),
                    firmware = reader.firmware(),
                )
                val current = state()
                val report = UnknownsReport.markdown(
                    info = current.info,
                    unknowns = current.unknowns,
                    application = "TV Slim pour Windows ${AppInfo.VERSION}",
                    survey = survey,
                    fromCatalog = current.lines.associate { it.entry to it.state },
                )
                withContext(Dispatchers.IO) { target.writeText(report) }
            }
                .onSuccess {
                    if (thenOpen == null) {
                        show(text(Res.string.msg_unknown_exported, target.path))
                    } else {
                        thenOpen(CatalogSuggestion.link(state().info, AppInfo.GITHUB_REPOSITORY))
                        show(text(Res.string.msg_unknown_proposed, target.path))
                    }
                }
                .onFailure { show(text(Res.string.msg_unknown_export_failed, it.message.orEmpty())) }
        }
    }

    // --- APK install ----------------------------------------------------------------------

    /**
     * Examines an APK picked or dropped on the window, and what the TV already has. Nothing is sent before
     * confirmation, which shows the package, its version and what it replaces.
     */
    fun chooseApk(file: File) {
        val activeInstallation = installation() ?: return show(text(Res.string.msg_connect_first))
        if (state().installation.busy) return show(text(Res.string.msg_apk_busy))
        scope.launch {
            updateInstallation { it.copy(phase = InstallationPhase.Review) }
            val review = activeInstallation.examine(file, file.name)
            updateInstallation { it.copy(phase = null) }
            when (review) {
                is ApkReview.Ready -> updateState { it.copy(confirmation = Confirmation.Installation(review.apk)) }
                is ApkReview.Rejected -> show(rejectionMessage(review))
            }
        }
    }

    /** Uploads then installs after confirmation; progress follows the upload, then Android's install. */
    fun installApk(apk: ChosenApk) {
        val activeInstallation = installation() ?: return show(text(Res.string.msg_connect_first))
        scope.launch {
            updateInstallation { InstallationState(phase = InstallationPhase.Upload(0, apk.size)) }
            val result = activeInstallation.install(apk) { sent, total ->
                val phase = if (sent >= total) InstallationPhase.Installation else InstallationPhase.Upload(sent, total)
                updateInstallation { it.copy(phase = phase) }
            }
            updateInstallation { InstallationState(last = result) }
            show(
                when (result) {
                    is InstallationResult.Succeeded -> text(Res.string.msg_apk_installed, apk.manifest.packageName)
                    is InstallationResult.Failed -> text(Res.string.msg_apk_failed, text(result.cause.resource()))
                },
            )
            if (SupportInvitation.deserves(result)) thank()
            // Package counts changed, and the new app may be a launcher.
            refresh()
        }
    }

    private fun rejectionMessage(review: ApkReview.Rejected): UiMessage = when (review.rejection) {
        ApkRejection.NOT_AN_APK -> text(Res.string.msg_apk_not_apk)
        ApkRejection.BATCH -> text(Res.string.msg_apk_bundle)
        ApkRejection.INVALID_PACKAGE -> text(Res.string.msg_apk_invalid_package)
        ApkRejection.ANDROID_TOO_OLD -> text(Res.string.msg_apk_sdk, review.minSdk ?: 0, review.tvSdk ?: 0)
        ApkRejection.TV_UNREACHABLE -> text(Res.string.msg_apk_unreachable)
    }

    private fun updateInstallation(transformation: (InstallationState) -> InstallationState) =
        updateState { it.copy(installation = transformation(it.installation)) }

    // --- Free ADB command -----------------------------------------------------------------

    fun enterCommand(rawValue: String) = updateCommand { it.copy(input = rawValue, recall = -1) }

    /** Up and Down in the field, as in a terminal. */
    fun recallCommand(older: Boolean) = updateCommand { it.withRecall(older) }

    /**
     * Sends the typed command once and keeps its output on screen. No confirmation or safeguard: the card
     * warns about it, and every send is logged.
     */
    fun sendCommand() {
        val current = state()
        if (!current.connected) return show(text(Res.string.msg_connect_first))
        if (current.command.inProgress) return
        when (val input = AdbConsole.read(current.command.input)) {
            is CommandInput.Rejected -> show(
                text(
                    when (input.rejection) {
                        CommandRejection.EMPTY -> Res.string.msg_command_empty
                        CommandRejection.NOT_SHELL -> Res.string.msg_command_not_shell
                        CommandRejection.TOO_LONG -> Res.string.msg_command_too_long
                    },
                ),
            )

            is CommandInput.Ready -> scope.launch {
                updateCommand { it.copy(inProgress = true) }
                val exchange = console.send(input.command)
                updateCommand { it.withSentCommand(input.command).copy(inProgress = false, last = exchange) }
                // It may have changed what other tabs show: packages, home screen, counters.
                refresh()
            }
        }
    }

    private fun updateCommand(transformation: (CommandState) -> CommandState) =
        updateState { it.copy(command = transformation(it.command)) }

    private fun AppState.states() = lines.associate { it.entry.packageName to it.state }

    private companion object {
        const val WATCH_DURATION_MS = 3 * 60 * 1000L
        const val WATCH_INTERVAL_MS = 5_000L
    }
}
