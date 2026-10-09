package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import net.jolabs40.tvslim.command.ShizukuRelaunch
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
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import java.io.File
import java.io.FileNotFoundException

/**
 * TV configuration: home screen (recommended launcher's store page, watching for its install), saving and
 * re-applying a configuration, exporting packages missing from the catalogue, installing an APK from the phone,
 * and the free-form ADB command.
 *
 * Shares only state and engine with the main view model. Files go through the Android picker, so nothing is
 * read or written unless the user picked it.
 */
class ConfigurationController(
    private val context: Context,
    private val reader: RemoteReader,
    private val engine: () -> DebloatEngine?,
    private val installation: () -> ApkInstallation?,
    private val console: AdbConsole,
    private val state: () -> RemoteState,
    private val updateState: ((RemoteState) -> RemoteState) -> Unit,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    private val refresh: () -> Unit,
    private val finish: (List<ActionResult>) -> Unit,
    /** Called after a successful re-apply or install; may show the support banner. */
    private val thank: () -> Unit,
) {

    /** Watches for a launcher whose store page was just opened. */
    private var watchJob: Job? = null

    /** Called on disconnect: the watch and the install outcome belong to the previous TV. */
    fun forget() {
        watchJob?.cancel()
        watchJob = null
        updateInstallation { InstallationState() }
        // Keep the command history; the last output came from the previous TV.
        updateCommand { CommandState(input = it.input, history = it.history) }
    }

    // --- Home screen ----------------------------------------------------------------------

    /**
     * Opens a launcher's page in the TV's store. The user confirms the install with the remote; the store is
     * the recommended path even when an APK exists.
     */
    fun installLauncher(packageName: String) {
        // The card offers nothing on non-TV devices; do nothing if reached another way.
        if (!state().info.deviceType.forCatalog) return
        val activeEngine = engine()
        if (activeEngine == null) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
        scope.launch {
            val result = activeEngine.openStoreListing(packageName)
            if (!result.succeeded) {
                show(context.getString(R.string.msg_store_failed, result.text(context)))
                return@launch
            }
            show(context.getString(R.string.msg_store_opened))
            watchInstallation(packageName)
        }
    }

    /**
     * Makes an installed launcher the TV's home screen. Logged, so it can be undone from the Log tab. The home
     * is read back afterwards because Android answers `Success` without changing anything while a higher-priority
     * factory home is still enabled (Google TV on the TCL).
     */
    fun setHome(component: String) {
        // The card offers nothing on non-TV devices; do nothing if reached another way.
        if (!state().info.deviceType.forCatalog) return
        val activeEngine = engine()
        if (activeEngine == null) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
        val info = state().info
        val packageName = component.substringBefore('/')
        val name = state().catalog.launcherName(packageName) ?: packageName
        scope.launch {
            val result = activeEngine.setHome(component, info.homeComponent.ifBlank { component })
            if (!result.succeeded) {
                show(context.getString(R.string.msg_home_failed, result.text(context).ifBlank { "—" }))
                return@launch
            }
            val inPlace = reader.currentHome()
            refresh()
            show(
                if (inPlace == packageName) {
                    context.getString(R.string.msg_home_set, name)
                } else {
                    context.getString(R.string.msg_home_kept, state().catalog.launcherName(inPlace) ?: inPlace)
                },
            )
        }
    }

    /**
     * Polls for the launcher after opening its store page, since the user is at the TV rather than the phone.
     * Every five seconds, for at most three minutes.
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
                        show(context.getString(R.string.msg_launcher_installed))
                        return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    // --- Save and re-apply ----------------------------------------------------------------

    /** Suggested file name: device and date. */
    fun fileName(): String = ConfigurationFile.suggestedName(state().info)

    /** Writes the TV's configuration, as last read, to the picked file. */
    fun save(target: Uri) {
        val current = state()
        if (!current.connected || current.lines.isEmpty()) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
        val text = ConfigurationFile.write(current.catalog.configurationOf(current.info, current.states()))
        scope.launch {
            runCatching { write(target, text) }
                .onSuccess { show(context.getString(R.string.msg_config_saved)) }
                .onFailure { show(context.getString(R.string.msg_config_save_failed, it.message.orEmpty())) }
        }
    }

    /**
     * Reads a saved configuration and compares it with the TV. Nothing is sent: changes go to confirmation, and
     * a TV that already matches just shows a message.
     */
    fun load(source: Uri) {
        if (!state().connected || engine() == null) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
        scope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(source)
                        ?: throw FileNotFoundException(source.toString())
                    stream.use { it.readBytes().decodeToString() }
                }
            }
            val configuration = loaded.getOrNull()?.let(ConfigurationFile::read)
            val current = state()
            when {
                loaded.isFailure -> show(
                    context.getString(R.string.msg_config_read_failed, loaded.exceptionOrNull()?.message.orEmpty()),
                )

                configuration == null -> show(context.getString(R.string.msg_config_invalid))
                else -> suggest(configuration.buildPlan(current.catalog, current.states(), current.info))
            }
        }
    }

    private fun suggest(plan: ReinjectionPlan) {
        val missingHome = plan.home
        when {
            !plan.nothingToDo -> updateState { it.copy(confirmation = Confirmation.Reinjection(plan)) }
            missingHome != null ->
                show(context.getString(R.string.msg_config_home_missing, missingHome.name))

            else -> show(context.getString(R.string.msg_config_up_to_date))
        }
    }

    /**
     * Offers to undo drift. Same confirmation, [Reinjector] and safeguards as a re-apply; only the plan's
     * origin differs.
     */
    fun suggestDriftRevert() {
        val plan = state().drift ?: return
        updateState { it.copy(confirmation = Confirmation.Reinjection(plan, drift = true)) }
    }

    /** Re-applies after confirmation, with progress and a summary like a batch apply. */
    fun reinject(plan: ReinjectionPlan) {
        val current = state()
        val activeEngine = engine()
        if (activeEngine == null) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
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

    // --- Unknown packages report ---------------------------------------------------------

    /** Suggested file name: device and date. */
    fun unknownsExportName(): String = UnknownsReport.suggestedName(state().info)

    /**
     * Writes a report of packages missing from the catalogue with what ADB says about each (location, privileges,
     * sensitive declarations, icon, RAM and storage), then the firmware and the catalogue entries the device has.
     * Everything is re-read at export time in four reads; nothing is written to the TV.
     *
     * With [suggest], the catalogue submission form then opens in the phone's browser; the user attaches the file.
     */
    fun exportUnknowns(target: Uri, suggest: Boolean = false) {
        if (!state().connected) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
        scope.launch {
            show(context.getString(R.string.msg_unknown_reading))
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
                    application = "${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    survey = survey,
                    fromCatalog = current.lines.associate { it.entry to it.state },
                )
                write(target, report)
            }
                .onSuccess {
                    show(if (suggest) openSuggestion() else context.getString(R.string.msg_unknown_exported))
                }
                .onFailure { show(context.getString(R.string.msg_unknown_export_failed, it.message.orEmpty())) }
        }
    }

    /** Opens the prefilled catalogue form and returns the message telling what to do with the file. */
    private fun openSuggestion(): String {
        val link = CatalogSuggestion.link(state().info)
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(view) }.isSuccess) {
            context.getString(R.string.msg_unknown_proposed)
        } else {
            context.getString(R.string.msg_unknown_no_browser, link)
        }
    }

    // --- APK install ---------------------------------------------------------------------

    /**
     * Examines an APK picked in the Android picker. It is first copied to the cache, since both dadb and the
     * manifest reader need a file. Nothing reaches the TV before confirmation.
     */
    fun chooseApk(source: Uri) {
        val activeInstallation = installation() ?: return show(context.getString(R.string.msg_connect_first))
        if (state().installation.busy) return show(context.getString(R.string.msg_apk_busy))
        scope.launch {
            updateInstallation { it.copy(phase = InstallationPhase.Review) }
            val name = displayName(source)
            val copied = runCatching { copy(source) }.getOrElse { error ->
                updateInstallation { it.copy(phase = null) }
                show(context.getString(R.string.msg_config_read_failed, error.message.orEmpty()))
                return@launch
            }
            val review = activeInstallation.examine(copied, name)
            updateInstallation { it.copy(phase = null) }
            when (review) {
                is ApkReview.Ready -> updateState { it.copy(confirmation = Confirmation.Installation(review.apk)) }
                is ApkReview.Rejected -> {
                    withContext(Dispatchers.IO) { copied.delete() }
                    show(rejectionMessage(review))
                }
            }
        }
    }

    /** Uploads and installs after confirmation; progress follows the upload, then Android's install. */
    fun installApk(apk: ChosenApk) {
        val activeInstallation = installation() ?: return show(context.getString(R.string.msg_connect_first))
        scope.launch {
            updateInstallation { InstallationState(phase = InstallationPhase.Upload(0, apk.size)) }
            val result = activeInstallation.install(apk) { sent, total ->
                val phase = if (sent >= total) InstallationPhase.Installation else InstallationPhase.Upload(sent, total)
                updateInstallation { it.copy(phase = phase) }
            }
            withContext(Dispatchers.IO) { apk.file.delete() }
            updateInstallation { InstallationState(last = result) }
            show(
                when (result) {
                    is InstallationResult.Succeeded ->
                        context.getString(R.string.msg_apk_installed, apk.manifest.packageName)

                    is InstallationResult.Failed ->
                        context.getString(R.string.msg_apk_failed, context.getString(result.cause.resource()))
                },
            )
            if (SupportInvitation.deserves(result)) thank()
            // Package counts changed, and the new app may be a launcher.
            refresh()
        }
    }

    /** Confirmation declined: deletes the cached copy. */
    fun abandonApk(apk: ChosenApk) {
        scope.launch(Dispatchers.IO) { apk.file.delete() }
    }

    private fun rejectionMessage(review: ApkReview.Rejected): String = when (review.rejection) {
        ApkRejection.NOT_AN_APK -> context.getString(R.string.msg_apk_not_apk)
        ApkRejection.BATCH -> context.getString(R.string.msg_apk_bundle)
        ApkRejection.INVALID_PACKAGE -> context.getString(R.string.msg_apk_invalid_package)
        ApkRejection.ANDROID_TOO_OLD ->
            context.getString(R.string.msg_apk_sdk, review.minSdk ?: 0, review.tvSdk ?: 0)

        ApkRejection.TV_UNREACHABLE -> context.getString(R.string.msg_apk_unreachable)
    }

    /** Display name of the picked file; for display only, never used in a path. */
    private suspend fun displayName(source: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: source.lastPathSegment ?: COPY_NAME
    }

    /**
     * Copies the APK to the cache under a fixed name, since the display name comes from another app. Clearing
     * the folder also removes any copy left by an abandoned attempt.
     */
    private suspend fun copy(source: Uri): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, APK_FOLDER).apply {
            deleteRecursively()
            mkdirs()
        }
        val copied = File(folder, COPY_NAME)
        val stream = context.contentResolver.openInputStream(source) ?: throw FileNotFoundException(source.toString())
        stream.use { entry -> copied.outputStream().use { output -> entry.copyTo(output) } }
        copied
    }

    private fun updateInstallation(transformation: (InstallationState) -> InstallationState) =
        updateState { it.copy(installation = transformation(it.installation)) }

    // --- Free-form ADB command ------------------------------------------------------------

    fun enterCommand(rawValue: String) = updateCommand { it.copy(input = rawValue) }

    /**
     * Sends the typed command once and keeps its output on screen. No confirmation and no safeguard (the card
     * says so); every command is logged.
     */
    fun sendCommand() {
        val current = state()
        if (!current.connected) return show(context.getString(R.string.msg_connect_first))
        if (current.command.inProgress) return
        when (val input = AdbConsole.read(current.command.input)) {
            is CommandInput.Rejected -> show(
                context.getString(
                    when (input.rejection) {
                        CommandRejection.EMPTY -> R.string.msg_command_empty
                        CommandRejection.NOT_SHELL -> R.string.msg_command_not_shell
                        CommandRejection.TOO_LONG -> R.string.msg_command_too_long
                    },
                ),
            )

            is CommandInput.Ready -> scope.launch {
                updateCommand { it.copy(inProgress = true) }
                val exchange = console.send(input.command)
                updateCommand { it.withSentCommand(input.command).copy(inProgress = false, last = exchange) }
                // The command may have changed what other tabs show.
                refresh()
            }
        }
    }

    private fun updateCommand(transformation: (CommandState) -> CommandState) =
        updateState { it.copy(command = transformation(it.command)) }

    // --- Shizuku restart -------------------------------------------------------------------

    /**
     * Restarts the TV's Shizuku service (see [ShizukuRelaunch]).
     *
     * Unlike the free-form command, the command is a core constant, never user input. It still goes through the
     * console, so it is logged and sent once, never replayed after a broken session. Other tabs are not
     * refreshed: starting a service changes neither packages, home nor memory.
     */
    fun relaunchShizuku() {
        val current = state()
        if (!current.connected) return show(context.getString(R.string.msg_connect_first))
        if (current.shizuku.inProgress) return
        scope.launch {
            updateShizuku { it.copy(inProgress = true) }
            val exchange = console.send(ShizukuRelaunch.COMMAND)
            updateShizuku { ShizukuState(inProgress = false, last = exchange) }
        }
    }

    private fun updateShizuku(transformation: (ShizukuState) -> ShizukuState) =
        updateState { it.copy(shizuku = transformation(it.shizuku)) }

    /** Mode "wt" truncates, so shorter content does not leave the old file's tail behind. */
    private suspend fun write(target: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(target, "wt")
            ?: throw FileNotFoundException(target.toString())
        stream.use { it.write(text.toByteArray()) }
    }

    private fun RemoteState.states() = lines.associate { it.entry.packageName to it.state }

    private companion object {
        const val WATCH_DURATION_MS = 3 * 60 * 1000L
        const val WATCH_INTERVAL_MS = 5_000L
        const val APK_FOLDER = "apk"
        const val COPY_NAME = "application.apk"
    }
}
