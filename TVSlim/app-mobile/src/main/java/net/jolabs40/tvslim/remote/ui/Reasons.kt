package net.jolabs40.tvslim.remote.ui

import android.content.Context
import net.jolabs40.tvslim.engine.EngineReason
import net.jolabs40.tvslim.engine.NameKind
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.remote.R

/** Describes an action's outcome: the engine's reason from resources if any, otherwise the TV's raw output. */
fun ActionResult.text(context: Context): String = reason?.phrase(context) ?: message

/** Localizes a [EngineReason]; the core, shared with Windows, contains no user-facing text. */
fun EngineReason.phrase(context: Context): String = when (this) {
    EngineReason.MissingPackage -> context.getString(R.string.engine_absent)
    EngineReason.AlreadyDisabled -> context.getString(R.string.engine_already_disabled)
    EngineReason.UnexplainedFailure -> context.getString(R.string.engine_unexplained)
    EngineReason.NoThirdPartyLauncher -> context.getString(R.string.engine_no_launcher)
    EngineReason.NoActivity -> context.getString(R.string.engine_no_activity)
    EngineReason.NotInstalledByUser -> context.getString(R.string.engine_not_user_installed)
    is EngineReason.Protected -> context.getString(R.string.engine_protected, protectionReason)
    is EngineReason.UnknownAppOpMode -> context.getString(R.string.engine_unknown_appop_mode, mode)
    is EngineReason.PermissionNotRequested -> context.getString(R.string.engine_permission_not_requested, packageName, permission)
    is EngineReason.InvalidName -> context.getString(
        when (kind) {
            NameKind.PACKAGE_NAME -> R.string.engine_invalid_package
            NameKind.PERMISSION -> R.string.engine_invalid_permission
            NameKind.APP_OP -> R.string.engine_invalid_appop
            NameKind.COMPONENT -> R.string.engine_invalid_component
        },
        rawValue,
    )
}
