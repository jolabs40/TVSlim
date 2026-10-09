package net.jolabs40.tvslim.remote.ui

import net.jolabs40.tvslim.device.PermissionsPaquet

/**
 * State of the "Permissions" card on the TV tab.
 *
 * [lues] is null until the TV has been queried. [paquetLu] records which package was read, so switching
 * packages without re-reading does not show stale state.
 */
data class EtatPermissions(
    val paquet: String = "",
    val permission: String = "",
    val lecture: Boolean = false,
    val lues: PermissionsPaquet? = null,
    val paquetLu: String = "",
    val modeAppOp: String = "",
) {
    /** App-op paired with the selected permission, if any. */
    val appOp: String get() = APP_OPS_ASSOCIES[permission].orEmpty()

    /** True when [lues] describes the package currently entered. */
    val aJour: Boolean get() = lues != null && paquetLu == paquet

    val accordee: Boolean get() = aJour && lues?.estAccordee(permission) == true

    val declaree: Boolean get() = aJour && lues?.estDeclaree(permission) == true

    val paquetIntrouvable: Boolean get() = aJour && lues?.paquetTrouve == false

    val saisieComplete: Boolean get() = paquet.isNotBlank() && permission.isNotBlank()
}

/** Grouped card callbacks; the connection screen already takes eleven parameters. */
data class ActionsPermissions(
    val onPaquet: (String) -> Unit,
    val onPermission: (String) -> Unit,
    val onLire: () -> Unit,
    val onAccorder: () -> Unit,
    val onRetirer: () -> Unit,
    /** An app picked from the TV's list. */
    val onChoisirPaquet: (String) -> Unit,
    /** Loads the app list, shared with the Apps tab, when it has not been read yet. */
    val onChargerApplications: () -> Unit,
)

/**
 * Permissions that Android also gates behind an app-op, with that op.
 *
 * Granting `PACKAGE_USAGE_STATS` without setting `GET_USAGE_STATS` makes `pm grant` succeed while the app
 * still sees nothing: both must be set.
 */
val APP_OPS_ASSOCIES = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to "GET_USAGE_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS" to "WRITE_SETTINGS",
)
