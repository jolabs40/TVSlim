package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.engine.EngineReason
import net.jolabs40.tvslim.engine.NameKind
import net.jolabs40.tvslim.engine.ActionResult
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.engine_absent
import net.jolabs40.tvslim.windows.resources.engine_already_disabled
import net.jolabs40.tvslim.windows.resources.engine_invalid_appop
import net.jolabs40.tvslim.windows.resources.engine_invalid_component
import net.jolabs40.tvslim.windows.resources.engine_invalid_package
import net.jolabs40.tvslim.windows.resources.engine_invalid_permission
import net.jolabs40.tvslim.windows.resources.engine_no_activity
import net.jolabs40.tvslim.windows.resources.engine_no_launcher
import net.jolabs40.tvslim.windows.resources.engine_not_user_installed
import net.jolabs40.tvslim.windows.resources.engine_permission_not_requested
import net.jolabs40.tvslim.windows.resources.engine_protected
import net.jolabs40.tvslim.windows.resources.engine_unexplained
import net.jolabs40.tvslim.windows.resources.engine_unknown_appop_mode

/** The engine's reason if there is one, otherwise the TV's raw reply. */
fun ActionResult.text(): UiMessage = reason?.message() ?: UiMessage.Raw(message)

/** Maps a [EngineReason] to a string resource. The core is shared with Android and holds no UI text. */
fun EngineReason.message(): UiMessage = when (this) {
    EngineReason.MissingPackage -> text(Res.string.engine_absent)
    EngineReason.AlreadyDisabled -> text(Res.string.engine_already_disabled)
    EngineReason.UnexplainedFailure -> text(Res.string.engine_unexplained)
    EngineReason.NoThirdPartyLauncher -> text(Res.string.engine_no_launcher)
    EngineReason.NoActivity -> text(Res.string.engine_no_activity)
    EngineReason.NotInstalledByUser -> text(Res.string.engine_not_user_installed)
    is EngineReason.Protected -> text(Res.string.engine_protected, protectionReason)
    is EngineReason.UnknownAppOpMode -> text(Res.string.engine_unknown_appop_mode, mode)
    is EngineReason.PermissionNotRequested -> text(Res.string.engine_permission_not_requested, packageName, permission)
    is EngineReason.InvalidName -> text(
        when (kind) {
            NameKind.PACKAGE_NAME -> Res.string.engine_invalid_package
            NameKind.PERMISSION -> Res.string.engine_invalid_permission
            NameKind.APP_OP -> Res.string.engine_invalid_appop
            NameKind.COMPONENT -> Res.string.engine_invalid_component
        },
        rawValue,
    )
}
