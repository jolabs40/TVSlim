package net.jolabs40.tvslim.remote.ui

import android.content.Context
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
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.remote.R

/**
 * Grants TV apps permissions they cannot obtain themselves (`DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
 * `PACKAGE_USAGE_STATS`), using the ADB session's shell privileges.
 *
 * The target is a third-party app rather than a catalogue package, so everything goes through the engine's
 * safeguards, which reject suspicious input and permissions the manifest does not declare.
 *
 * Some permissions are also gated by an app-op set with a separate command; granting one without the other
 * makes `pm grant` succeed while the app still sees nothing. Both are granted and revoked together.
 */
class PermissionsController(
    private val context: Context,
    private val reader: RemoteReader,
    private val engine: () -> DebloatEngine?,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
) {

    private val _state = MutableStateFlow(PermissionsState())
    val state: StateFlow<PermissionsState> = _state.asStateFlow()

    /** Changing the package discards what was read for the previous one. */
    fun updatePackage(rawValue: String) = _state.update {
        it.copy(packageName = rawValue.trim(), fetched = null, loadedPackage = "", appOpMode = "")
    }

    /** Selects an app from the list and reads its permissions right away. */
    fun choosePackage(packageName: String) {
        updatePackage(packageName)
        read()
    }

    /** Changing the permission discards the app-op mode read for the previous one. */
    fun updatePermission(rawValue: String) = _state.update {
        it.copy(permission = rawValue.trim(), appOpMode = "")
    }

    /** Called on disconnect: the state belongs to the previous TV. */
    fun forget() = _state.update { PermissionsState() }

    /** Reads what the app declares and what it has already been granted. */
    fun read() {
        val packageName = _state.value.packageName
        if (packageName.isBlank()) {
            show(context.getString(R.string.msg_perm_enter_package))
            return
        }
        if (engine() == null) {
            show(context.getString(R.string.msg_connect_first))
            return
        }
        scope.launch { reread(packageName) }
    }

    fun grant() = act { packageName, permission, activeEngine ->
        val fetched = reread(packageName)
        if (!fetched.packageFound) {
            show(context.getString(R.string.msg_perm_not_found, packageName))
            return@act
        }
        val result = activeEngine.grantPermission(packageName, permission, fetched.requested)
        if (!result.succeeded) {
            show(failure(result))
            return@act
        }
        val extra = applyAppOp(packageName, permission, MODE_ALLOWED, activeEngine)
        show(
            sentences(
                context.getString(R.string.msg_perm_granted, permission),
                extra,
                context.getString(R.string.msg_perm_reopen),
            ),
        )
        reread(packageName)
    }

    fun revoke() = act { packageName, permission, activeEngine ->
        val result = activeEngine.revokePermission(packageName, permission)
        if (!result.succeeded) {
            show(failure(result))
            return@act
        }
        // Reset the app-op to "default" rather than "ignore": it may not have been denied before, and
        // "default" lets the permission decide.
        val extra = applyAppOp(packageName, permission, MODE_DEFAULT, activeEngine)
        show(sentences(context.getString(R.string.msg_perm_revoked, permission), extra))
        reread(packageName)
    }

    /**
     * Undoes a log entry. Its target is written as "package name"; the engine's safeguards already rejected
     * anything with an extra space.
     */
    fun undo(action: JournalAction) {
        val activeEngine = engine()
        val parts = action.target.split(' ')
        if (activeEngine == null || parts.size != 2) {
            show(context.getString(R.string.msg_not_undoable))
            return
        }
        scope.launch {
            val (packageName, name) = parts
            val result = when {
                action.type == ActionType.APP_OP -> activeEngine.setAppOp(
                    packageName = packageName,
                    appOp = name,
                    // The log stores the full command; its last word is the target mode.
                    mode = action.undoCommand.substringAfterLast(' '),
                    previousMode = reader.appOpMode(packageName, name),
                )

                // A grant is undone by a revoke, and vice versa.
                action.undoCommand.contains(" revoke ") ->
                    activeEngine.revokePermission(packageName, name)

                else -> activeEngine.grantPermission(
                    packageName = packageName,
                    permission = name,
                    declaredPermissions = reader.permissions(packageName).requested,
                )
            }
            show(if (result.succeeded) context.getString(R.string.msg_undone) else failure(result))
            if (packageName == _state.value.packageName) reread(packageName)
        }
    }

    /**
     * Sets the app-op paired with the permission, if any, and returns a one-sentence outcome. Skipped when the
     * op is already in the requested mode.
     */
    private suspend fun applyAppOp(
        packageName: String,
        permission: String,
        mode: String,
        activeEngine: DebloatEngine,
    ): String {
        val appOp = ASSOCIATED_APP_OPS[permission] ?: return ""
        val current = reader.appOpMode(packageName, appOp)
        if (current == mode) return ""

        val result = activeEngine.setAppOp(packageName, appOp, mode, current)
        return if (result.succeeded) {
            context.getString(R.string.msg_appop_set, appOp, mode)
        } else {
            context.getString(R.string.msg_appop_failed, appOp, result.text(context))
        }
    }

    private fun act(
        block: suspend (packageName: String, permission: String, engine: DebloatEngine) -> Unit,
    ) {
        val current = _state.value
        val activeEngine = engine()
        when {
            activeEngine == null -> show(context.getString(R.string.msg_connect_first))
            !current.inputComplete -> show(context.getString(R.string.msg_perm_enter_both))
            else -> scope.launch {
                block(current.packageName, current.permission, activeEngine)
            }
        }
    }

    private fun failure(result: ActionResult): String = context.getString(R.string.msg_failure, result.text(context))

    /** Joins sentences, skipping blank ones. */
    private fun sentences(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString(" ")

    /** Re-reads the package's permissions and paired app-op, and publishes them. */
    private suspend fun reread(packageName: String): PackagePermissions {
        val permission = _state.value.permission
        _state.update { it.copy(reading = true) }

        val fetched = reader.permissions(packageName)
        val mode = ASSOCIATED_APP_OPS[permission]
            ?.let { reader.appOpMode(packageName, it) }
            .orEmpty()

        _state.update { current ->
            // Discard the result if the user changed the target meanwhile.
            if (current.packageName != packageName || current.permission != permission) {
                current.copy(reading = false)
            } else {
                current.copy(
                    reading = false,
                    fetched = fetched,
                    loadedPackage = packageName,
                    appOpMode = mode,
                )
            }
        }
        return fetched
    }

    private companion object {
        const val MODE_ALLOWED = "allow"
        const val MODE_DEFAULT = "default"
    }
}
