package net.jolabs40.tvslim.engine

/**
 * What the engine says about an action (a refusal, or a failure the TV gave no reason for), as a typed
 * reason that each app words in the user's language from its own resources.
 *
 * The core writes no sentences: it is compiled into the Android companion and the Windows app, whose UIs
 * default to English. The TV's own answer stays untranslated in [ActionResult.message].
 */
sealed interface EngineReason {

    /** The package is not on this device. */
    data object MissingPackage : EngineReason

    /** Nothing to do, already disabled; comes with a success. */
    data object AlreadyDisabled : EngineReason

    /** The TV refused without any message. */
    data object UnexplainedFailure : EngineReason

    /** A name bound for the shell is not a valid identifier; rejected before sending. */
    data class InvalidName(val kind: NameKind, val rawValue: String) : EngineReason

    /** An app-op mode that `appops` does not accept. */
    data class UnknownAppOpMode(val mode: String) : EngineReason

    /** On the catalogue blocklist. [protectionReason] comes from the catalogue, already in the device language. */
    data class Protected(val protectionReason: String) : EngineReason

    /** The stock home screen cannot be disabled without a third-party launcher: the TV would boot to nothing. */
    data object NoThirdPartyLauncher : EngineReason

    /** The app does not request this permission in its manifest, so there is nothing to grant. */
    data class PermissionNotRequested(val packageName: String, val permission: String) : EngineReason

    /** The app has no launcher activity to open. */
    data object NoActivity : EngineReason

    /** Not an app installed by the user, so it cannot be uninstalled here. */
    data object NotInstalledByUser : EngineReason
}

/** What a [EngineReason.InvalidName] was supposed to name. */
enum class NameKind { PACKAGE_NAME, PERMISSION, APP_OP, COMPONENT }

/** Reason for a failure the TV said nothing about; null when it did answer something. */
internal fun reasonIfSilent(succeeded: Boolean, output: String): EngineReason? =
    if (!succeeded && output.isBlank()) EngineReason.UnexplainedFailure else null
