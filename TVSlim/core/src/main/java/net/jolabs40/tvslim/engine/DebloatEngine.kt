package net.jolabs40.tvslim.engine

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.CommandExecutor

data class ActionResult(
    val packageName: String,
    val name: String,
    val succeeded: Boolean,
    /** The TV's raw answer. */
    val message: String = "",
    /** The engine's reason, worded by each app; takes precedence over [message] when set. */
    val reason: EngineReason? = null,
)

/**
 * Applies and undoes package disabling, with the safeguards learned from the manual debloat of the
 * TCL:
 *
 *  - never `pm uninstall`: only `pm disable-user --user 0`, undone by `pm enable`;
 *  - blocklisted catalogue packages are always refused;
 *  - the stock home screen is not touched until a third-party launcher is installed;
 *  - the declared order is kept so `setupwraith` goes before `launcherx`; otherwise its
 *    priority-1 RecoveryActivity would take over instead of the chosen launcher.
 *
 * The engine does not know which channel the commands go through (local service or ADB). It writes
 * no text either: its refusals are [EngineReason] values that each app words.
 */
class DebloatEngine(
    private val executor: CommandExecutor,
    private val journal: JournalRepository,
) {

    suspend fun disable(
        entries: List<PackageEntry>,
        catalog: Catalog,
        states: Map<String, PackageState>,
        launchersAvailable: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        val toLog = mutableListOf<JournalAction>()

        val toProcess = entries.sortedBy { it.order }
        toProcess.forEachIndexed { index, entry ->
            onProgress(index, toProcess.size)
            val rejection = rejectionReason(entry, catalog, launchersAvailable)
            if (rejection != null) {
                results += ActionResult(entry.packageName, entry.name, false, reason = rejection)
                return@forEachIndexed
            }
            when (states[entry.packageName] ?: PackageState.ABSENT) {
                PackageState.ABSENT -> {
                    results += ActionResult(entry.packageName, entry.name, false, reason = EngineReason.MissingPackage)
                    return@forEachIndexed
                }

                PackageState.DISABLED -> {
                    results += ActionResult(entry.packageName, entry.name, true, reason = EngineReason.AlreadyDisabled)
                    return@forEachIndexed
                }

                PackageState.ACTIVE -> Unit
            }

            val output = executor.execute("pm disable-user --user 0 ${entry.packageName}")
            val succeeded = output.succeeded && output.output.contains("disabled-user")
            val message = if (succeeded) "" else output.output

            results += ActionResult(entry.packageName, entry.name, succeeded, message, reasonIfSilent(succeeded, message))
            toLog += JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.DISABLING,
                target = entry.packageName,
                label = entry.name,
                undoCommand = "pm enable ${entry.packageName}",
                succeeded = succeeded,
                message = message,
            )
        }

        onProgress(toProcess.size, toProcess.size)
        journal.add(toLog)
        return results
    }

    suspend fun enable(
        packages: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        val toLog = mutableListOf<JournalAction>()

        packages.forEachIndexed { index, packageName ->
            onProgress(index, packages.size)
            val output = executor.execute("pm enable $packageName")
            val succeeded = output.succeeded && output.output.contains("enabled")
            val message = if (succeeded) "" else output.output

            results += ActionResult(packageName, packageName, succeeded, message, reasonIfSilent(succeeded, message))
            toLog += JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.ENABLING,
                target = packageName,
                label = packageName,
                undoCommand = "pm disable-user --user 0 $packageName",
                succeeded = succeeded,
                message = message,
            )
        }

        onProgress(packages.size, packages.size)
        journal.add(toLog)
        return results
    }

    /**
     * Sets the home activity. Call only once the stock home screen is disabled: while it is enabled,
     * the command answers `Success` with no effect.
     *
     * The result is named after the component, which a failure summary can quote untranslated.
     */
    suspend fun setHome(component: String, oldHome: String): ActionResult {
        // Both go into commands, and the undo one is replayed as is from the journal long after.
        // They come from `cmd package` output, a constrained source, but checking only one of them
        // would leave the replay unchecked.
        val rejection = componentRejectionReason(component) ?: componentRejectionReason(oldHome)
        if (rejection != null) return ActionResult(component, component, false, reason = rejection)

        val output = executor.execute("cmd package set-home-activity $component")
        journal.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.HOME,
                target = component,
                // Journal screens name this type themselves; this label is only used by the export.
                label = HOME_LABEL,
                undoCommand = "cmd package set-home-activity $oldHome",
                succeeded = output.succeeded,
                message = if (output.succeeded) "" else output.output,
            ),
        )
        return ActionResult(component, component, output.succeeded, output.output)
    }

    /**
     * Opens an app's page in the TV's own store; the person at the TV confirms the install with
     * the remote.
     *
     * This is the path for a recommended launcher: nothing is downloaded, the install comes from
     * the official store. An APK the user already has goes through `ApkInstallation`, on request.
     */
    suspend fun openStoreListing(packageName: String): ActionResult {
        if (!IDENTIFIER.matches(packageName)) {
            return ActionResult(packageName, packageName, false, reason = EngineReason.InvalidName(NameKind.PACKAGE_NAME, packageName))
        }
        val output = executor.execute(
            "am start -a android.intent.action.VIEW -d market://details?id=$packageName",
        )
        return ActionResult(packageName, packageName, output.succeeded, output.output)
    }

    /**
     * Force-stops an app's processes. Nothing is journaled: it is a reset, not a state change, and
     * the app restarts as soon as it is opened or a service calls it.
     */
    suspend fun forceStop(packageName: String): ActionResult {
        // This name is parsed from `dumpsys meminfo` output, the only one in the engine not taken
        // from the catalogue or a package list.
        if (!IDENTIFIER.matches(packageName)) {
            return ActionResult(packageName, packageName, false, reason = EngineReason.InvalidName(NameKind.PACKAGE_NAME, packageName))
        }
        val output = executor.execute("am force-stop $packageName")
        return ActionResult(packageName, packageName, output.succeeded, output.output, reasonIfSilent(output.succeeded, output.output))
    }

    /** Writes a system setting value and journals its undo command. */
    suspend fun writeSetting(
        key: String,
        scope: String,
        name: String,
        rawValue: String,
        previousValue: String,
    ): ActionResult {
        val output = executor.execute("settings put $scope $key $rawValue")
        journal.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.SETTING,
                target = key,
                label = name,
                undoCommand = "settings put $scope $key $previousValue",
                succeeded = output.succeeded,
                message = if (output.succeeded) "" else output.output,
            ),
        )
        return ActionResult(key, name, output.succeeded, output.output, reasonIfSilent(output.succeeded, output.output))
    }

    /**
     * Grants a TV app a permission no app can grant itself (`DUMP`, `WRITE_SECURE_SETTINGS`,
     * `READ_LOGS`). Only ADB can grant it, and the grant survives reboots.
     *
     * This is the only command built from typed text rather than the catalogue, hence two checks:
     * package and permission must be identifiers (a `;` would start a second command on the TV),
     * and the app must declare the permission (`pm grant` would otherwise fail with a Java exception
     * instead of a readable reason).
     */
    suspend fun grantPermission(
        packageName: String,
        permission: String,
        declaredPermissions: Set<String>,
    ): ActionResult {
        val rejection = permissionRejectionReason(packageName, permission, declaredPermissions)
        if (rejection != null) return ActionResult(packageName, permission, false, reason = rejection)
        return changePermission(packageName, permission, grant = true)
    }

    /** Revokes a granted permission. Revoking is always allowed, so the manifest is not checked. */
    suspend fun revokePermission(packageName: String, permission: String): ActionResult {
        val rejection = permissionRejectionReason(packageName, permission, declaredPermissions = null)
        if (rejection != null) return ActionResult(packageName, permission, false, reason = rejection)
        return changePermission(packageName, permission, grant = false)
    }

    private suspend fun changePermission(
        packageName: String,
        permission: String,
        grant: Boolean,
    ): ActionResult {
        val verb = if (grant) "grant" else "revoke"
        val inverse = if (grant) "revoke" else "grant"
        val output = executor.execute("pm $verb $packageName $permission")

        // `pm grant` prints nothing on success; any output is an exception from the TV.
        val succeeded = output.succeeded && output.output.isBlank()
        val message = if (succeeded) "" else output.output

        journal.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.PERMISSION,
                target = "$packageName $permission",
                label = "$packageName — ${permission.substringAfterLast('.')}",
                undoCommand = "pm $inverse $packageName $permission",
                succeeded = succeeded,
                message = message,
            ),
        )
        return ActionResult(packageName, permission, succeeded, message, reasonIfSilent(succeeded, message))
    }

    /**
     * Sets an app-op mode, Android's second lock next to permissions.
     *
     * Example: `pm grant ... PACKAGE_USAGE_STATS` succeeds, yet the app sees nothing while
     * `GET_USAGE_STATS` stays denied. The reverse holds too, hence [previousMode]: undo restores
     * the mode found before, not an assumed `default`.
     */
    suspend fun setAppOp(
        packageName: String,
        appOp: String,
        mode: String,
        previousMode: String,
    ): ActionResult {
        val rejection = when {
            !IDENTIFIER.matches(packageName) -> EngineReason.InvalidName(NameKind.PACKAGE_NAME, packageName)
            !IDENTIFIER.matches(appOp) -> EngineReason.InvalidName(NameKind.APP_OP, appOp)
            mode !in MODES_APP_OP -> EngineReason.UnknownAppOpMode(mode)
            else -> null
        }
        if (rejection != null) return ActionResult(packageName, appOp, false, reason = rejection)

        val output = executor.execute("cmd appops set $packageName $appOp $mode")

        // Like `pm grant`, `cmd appops set` prints nothing on success.
        val succeeded = output.succeeded && output.output.isBlank()
        val message = if (succeeded) "" else output.output
        val restoreMode = previousMode.ifBlank { DEFAULT_APP_OP_MODE }

        journal.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.APP_OP,
                target = "$packageName $appOp",
                label = "$packageName — $appOp",
                undoCommand = "cmd appops set $packageName $appOp $restoreMode",
                succeeded = succeeded,
                message = message,
            ),
        )
        return ActionResult(packageName, appOp, succeeded, message, reasonIfSilent(succeeded, message))
    }

    private fun componentRejectionReason(component: String): EngineReason? =
        if (COMPONENT.matches(component)) null else EngineReason.InvalidName(NameKind.COMPONENT, component)

    private fun permissionRejectionReason(
        packageName: String,
        permission: String,
        declaredPermissions: Set<String>?,
    ): EngineReason? = when {
        !IDENTIFIER.matches(packageName) -> EngineReason.InvalidName(NameKind.PACKAGE_NAME, packageName)
        !IDENTIFIER.matches(permission) -> EngineReason.InvalidName(NameKind.PERMISSION, permission)
        declaredPermissions != null && permission !in declaredPermissions ->
            EngineReason.PermissionNotRequested(packageName, permission)

        else -> null
    }

    private fun rejectionReason(
        entry: PackageEntry,
        catalog: Catalog,
        launchersAvailable: Boolean,
    ): EngineReason? = when {
        // The package goes into the shell, so it must be a plain identifier. Catalogue names are; those a phone
        // reports (Catalogue.avecApplicationsDuMenu) come from the device.
        !IDENTIFIER.matches(entry.packageName) -> EngineReason.InvalidName(NameKind.PACKAGE_NAME, entry.packageName)

        catalog.isProtected(entry.packageName) -> EngineReason.Protected(catalog.protectionReason(entry.packageName).orEmpty())

        entry.requiresThirdPartyLauncher && !launchersAvailable -> EngineReason.NoThirdPartyLauncher

        else -> null
    }

    private companion object {
        /** The TV shell takes the line as is, so only a plain identifier is allowed. */
        val IDENTIFIER = Regex("""[A-Za-z0-9_.]+""")

        /** E.g. `com.spocky.projengmenu/.MainActivity`: two identifiers and a slash, nothing more. */
        val COMPONENT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.]+""")

        const val DEFAULT_APP_OP_MODE = "default"

        /** The four modes `appops` accepts; anything else is a typo. */
        val MODES_APP_OP = setOf("allow", "deny", "ignore", DEFAULT_APP_OP_MODE)

        /** Label of a home screen journal entry, as written by the Markdown export. */
        const val HOME_LABEL = "Écran d'accueil"
    }
}
