package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.device.PermissionsPaquet

/**
 * State of the "Permissions" card on the TV tab, same as the companion.
 *
 * [lues] is `null` until the TV has been queried, and [paquetLu] records which package it describes, so
 * switching packages without re-reading does not pretend to know the new one's state.
 *
 * Both fields start empty with a placeholder example; no shortcut chips.
 */
data class EtatPermissions(
    val paquet: String = "",
    val permission: String = "",
    val lecture: Boolean = false,
    val lues: PermissionsPaquet? = null,
    val paquetLu: String = "",
    val modeAppOp: String = "",
) {
    /** App-op paired with the chosen permission, if any. */
    val appOp: String get() = APP_OPS_ASSOCIES[permission].orEmpty()

    /** True when [lues] describes the package currently entered. */
    val aJour: Boolean get() = lues != null && paquetLu == paquet

    val accordee: Boolean get() = aJour && lues?.estAccordee(permission) == true

    val declaree: Boolean get() = aJour && lues?.estDeclaree(permission) == true

    val paquetIntrouvable: Boolean get() = aJour && lues?.paquetTrouve == false

    val saisieComplete: Boolean get() = paquet.isNotBlank() && permission.isNotBlank()
}

data class ActionsPermissions(
    val onPaquet: (String) -> Unit,
    val onPermission: (String) -> Unit,
    val onLire: () -> Unit,
    val onAccorder: () -> Unit,
    val onRetirer: () -> Unit,
    /** An app picked from the TV's app list. */
    val onChoisirPaquet: (String) -> Unit,
    /** Loads that list, shared with the Applications tab, when it has not been read yet. */
    val onChargerApplications: () -> Unit,
)

/**
 * Permissions that Android also gates behind an app-op, mapped to that op.
 *
 * Granting `PACKAGE_USAGE_STATS` without allowing `GET_USAGE_STATS` gives a successful `pm grant` and an
 * app that still sees nothing: both must be set together.
 */
val APP_OPS_ASSOCIES = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to "GET_USAGE_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS" to "WRITE_SETTINGS",
)
