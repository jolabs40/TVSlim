package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.device.PackagePermissions
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.msg_appop_failed
import net.jolabs40.tvslim.windows.resources.msg_appop_set
import net.jolabs40.tvslim.windows.resources.msg_connect_first
import net.jolabs40.tvslim.windows.resources.msg_failure
import net.jolabs40.tvslim.windows.resources.msg_not_undoable
import net.jolabs40.tvslim.windows.resources.msg_perm_enter_both
import net.jolabs40.tvslim.windows.resources.msg_perm_enter_package
import net.jolabs40.tvslim.windows.resources.msg_perm_granted
import net.jolabs40.tvslim.windows.resources.msg_perm_not_found
import net.jolabs40.tvslim.windows.resources.msg_perm_reopen
import net.jolabs40.tvslim.windows.resources.msg_perm_revoked
import net.jolabs40.tvslim.windows.resources.msg_undone

/**
 * Grants TV apps permissions they cannot obtain on their own (`DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
 * `PACKAGE_USAGE_STATS`).
 *
 * The privilege is the ADB session's, the same one used for `pm disable-user`. Only the target differs (a
 * third-party app rather than a catalogue package), hence the engine's safeguards, which reject suspicious
 * input and permissions missing from the manifest.
 *
 * Some permissions are not enough alone: Android also gates them behind an app-op, so both are granted and
 * revoked together. Same logic as the companion, line for line.
 */
class PermissionsController(
    private val reader: RemoteReader,
    private val engine: () -> DebloatEngine?,
    private val scope: CoroutineScope,
    private val show: (UiMessage) -> Unit,
) {

    private val _state = MutableStateFlow(PermissionsState())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    /** Changing package discards what was read for the previous one. */
    fun updatePackage(rawValue: String) = _state.update {
        it.copy(packageName = rawValue.trim(), fetched = null, loadedPackage = "", appOpMode = "")
    }

    /** An app picked from the list: sets its package and reads what it declares right away. */
    fun choosePackage(packageName: String) {
        updatePackage(packageName)
        read()
    }

    /** Changing permission discards the app-op mode read for the previous one. */
    fun updatePermission(rawValue: String) = _state.update {
        it.copy(permission = rawValue.trim(), appOpMode = "")
    }

    /** Called on disconnect: read state belongs to one TV. */
    fun forget() = _state.update { PermissionsState() }

    /** Asks the TV what the app declares and what it has already been granted. */
    fun read() {
        val packageName = _state.value.packageName
        if (packageName.isBlank()) {
            show(text(Res.string.msg_perm_enter_package))
            return
        }
        if (engine() == null) {
            show(text(Res.string.msg_connect_first))
            return
        }
        scope.launch { reread(packageName) }
    }

    fun grant() = act { packageName, permission, activeEngine ->
        val fetched = reread(packageName)
        if (!fetched.packageFound) {
            show(text(Res.string.msg_perm_not_found, packageName))
            return@act
        }
        val result = activeEngine.grantPermission(packageName, permission, fetched.requested)
        if (!result.succeeded) {
            show(text(Res.string.msg_failure, result.text()))
            return@act
        }
        val extra = applyAppOp(packageName, permission, MODE_ALLOWED, activeEngine)
        show(
            UiMessage.Lines(
                listOf(
                    text(Res.string.msg_perm_granted, permission),
                    extra,
                    text(Res.string.msg_perm_reopen),
                ),
            ),
        )
        reread(packageName)
    }

    fun revoke() = act { packageName, permission, activeEngine ->
        val result = activeEngine.revokePermission(packageName, permission)
        if (!result.succeeded) {
            show(text(Res.string.msg_failure, result.text()))
            return@act
        }
        // Reset the app-op to "default" rather than "ignore": it may not have been denied before, and
        // "default" lets the permission decide, as originally.
        val extra = applyAppOp(packageName, permission, MODE_DEFAULT, activeEngine)
        show(UiMessage.Lines(listOf(text(Res.string.msg_perm_revoked, permission), extra)))
        reread(packageName)
    }

    /**
     * Undoes a journal line. Its target is stored as "package name"; the engine's safeguards already
     * rejected anything containing another space.
     */
    fun undo(action: JournalAction) {
        val activeEngine = engine()
        val parts = action.target.split(' ')
        if (activeEngine == null || parts.size != 2) {
            show(text(Res.string.msg_not_undoable))
            return
        }
        scope.launch {
            val (packageName, name) = parts
            val result = when {
                action.type == ActionType.APP_OP -> activeEngine.setAppOp(
                    packageName = packageName,
                    appOp = name,
                    // The journal stores the full command; its last word is the target mode.
                    mode = action.undoCommand.substringAfterLast(' '),
                    previousMode = reader.appOpMode(packageName, name),
                )

                // A grant is undone by revoking, and vice versa.
                action.undoCommand.contains(" revoke ") ->
                    activeEngine.revokePermission(packageName, name)

                else -> activeEngine.grantPermission(
                    packageName = packageName,
                    permission = name,
                    declaredPermissions = reader.permissions(packageName).requested,
                )
            }
            show(
                if (result.succeeded) {
                    text(Res.string.msg_undone)
                } else {
                    text(Res.string.msg_failure, result.text())
                },
            )
            if (packageName == _state.value.packageName) reread(packageName)
        }
    }

    /**
     * Sets the app-op paired with the permission, if any, and returns a one-line outcome. Skips the
     * round trip when the op is already in the wanted mode.
     */
    private suspend fun applyAppOp(
        packageName: String,
        permission: String,
        mode: String,
        activeEngine: DebloatEngine,
    ): UiMessage {
        val appOp = ASSOCIATED_APP_OPS[permission] ?: return UiMessage.Raw("")
        val current = reader.appOpMode(packageName, appOp)
        if (current == mode) return UiMessage.Raw("")

        val result = activeEngine.setAppOp(packageName, appOp, mode, current)
        return if (result.succeeded) {
            text(Res.string.msg_appop_set, appOp, mode)
        } else {
            text(Res.string.msg_appop_failed, appOp, result.text())
        }
    }

    private fun act(
        block: suspend (packageName: String, permission: String, engine: DebloatEngine) -> Unit,
    ) {
        val current = _state.value
        val activeEngine = engine()
        when {
            activeEngine == null -> show(text(Res.string.msg_connect_first))
            !current.inputComplete -> show(text(Res.string.msg_perm_enter_both))
            else -> scope.launch { block(current.packageName, current.permission, activeEngine) }
        }
    }

    /** Re-reads the package's permissions and paired app-op, and publishes them. */
    private suspend fun reread(packageName: String): PackagePermissions {
        val permission = _state.value.permission
        _state.update { it.copy(reading = true) }

        val fetched = reader.permissions(packageName)
        val mode = ASSOCIATED_APP_OPS[permission]
            ?.let { reader.appOpMode(packageName, it) }
            .orEmpty()

        _state.update { current ->
            // The user may have changed target during the round trip; if so, drop the result.
            if (current.packageName != packageName || current.permission != permission) {
                current.copy(reading = false)
            } else {
                current.copy(reading = false, fetched = fetched, loadedPackage = packageName, appOpMode = mode)
            }
        }
        return fetched
    }

    private companion object {
        const val MODE_ALLOWED = "allow"
        const val MODE_DEFAULT = "default"
    }
}
