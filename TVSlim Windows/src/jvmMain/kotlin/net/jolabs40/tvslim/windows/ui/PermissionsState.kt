package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.device.PackagePermissions

/**
 * State of the "Permissions" card on the TV tab, same as the companion.
 *
 * [fetched] is `null` until the TV has been queried, and [loadedPackage] records which package it describes, so
 * switching packages without re-reading does not pretend to know the new one's state.
 *
 * Both fields start empty with a placeholder example; no shortcut chips.
 */
data class PermissionsState(
    val packageName: String = "",
    val permission: String = "",
    val reading: Boolean = false,
    val fetched: PackagePermissions? = null,
    val loadedPackage: String = "",
    val appOpMode: String = "",
) {
    /** App-op paired with the chosen permission, if any. */
    val appOp: String get() = ASSOCIATED_APP_OPS[permission].orEmpty()

    /** True when [fetched] describes the package currently entered. */
    val upToDate: Boolean get() = fetched != null && loadedPackage == packageName

    val isGranted: Boolean get() = upToDate && fetched?.isGranted(permission) == true

    val isDeclared: Boolean get() = upToDate && fetched?.isDeclared(permission) == true

    val packageNotFound: Boolean get() = upToDate && fetched?.packageFound == false

    val inputComplete: Boolean get() = packageName.isNotBlank() && permission.isNotBlank()
}

data class PermissionsActions(
    val onPackage: (String) -> Unit,
    val onPermission: (String) -> Unit,
    val onRead: () -> Unit,
    val onGrant: () -> Unit,
    val onRevoke: () -> Unit,
    /** An app picked from the TV's app list. */
    val onChoosePackage: (String) -> Unit,
    /** Loads that list, shared with the Applications tab, when it has not been read yet. */
    val onLoadApps: () -> Unit,
)

/**
 * Permissions that Android also gates behind an app-op, mapped to that op.
 *
 * Granting `PACKAGE_USAGE_STATS` without allowing `GET_USAGE_STATS` gives a successful `pm grant` and an
 * app that still sees nothing: both must be set together.
 */
val ASSOCIATED_APP_OPS = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to "GET_USAGE_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS" to "WRITE_SETTINGS",
)
