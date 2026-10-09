package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.device.PackagePermissions

/**
 * State of the "Permissions" card on the TV tab.
 *
 * [fetched] is null until the TV has been queried. [loadedPackage] records which package was read, so switching
 * packages without re-reading does not show stale state.
 */
data class PermissionsState(
    val packageName: String = "",
    val permission: String = "",
    val reading: Boolean = false,
    val fetched: PackagePermissions? = null,
    val loadedPackage: String = "",
    val appOpMode: String = "",
) {
    /** App-op paired with the selected permission, if any. */
    val appOp: String get() = ASSOCIATED_APP_OPS[permission].orEmpty()

    /** True when [fetched] describes the package currently entered. */
    val upToDate: Boolean get() = fetched != null && loadedPackage == packageName

    val isGranted: Boolean get() = upToDate && fetched?.isGranted(permission) == true

    val isDeclared: Boolean get() = upToDate && fetched?.isDeclared(permission) == true

    val packageNotFound: Boolean get() = upToDate && fetched?.packageFound == false

    val inputComplete: Boolean get() = packageName.isNotBlank() && permission.isNotBlank()
}

/** Grouped card callbacks; the connection screen already takes eleven parameters. */
data class PermissionsActions(
    val onPackage: (String) -> Unit,
    val onPermission: (String) -> Unit,
    val onRead: () -> Unit,
    val onGrant: () -> Unit,
    val onRevoke: () -> Unit,
    /** An app picked from the TV's list. */
    val onChoosePackage: (String) -> Unit,
    /** Loads the app list, shared with the Apps tab, when it has not been read yet. */
    val onLoadApps: () -> Unit,
)

/**
 * Permissions that Android also gates behind an app-op, with that op.
 *
 * Granting `PACKAGE_USAGE_STATS` without setting `GET_USAGE_STATS` makes `pm grant` succeed while the app
 * still sees nothing: both must be set.
 */
val ASSOCIATED_APP_OPS = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to "GET_USAGE_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS" to "WRITE_SETTINGS",
)
