package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.device.PermissionsPaquet

/**
 * Ce que la carte « Permissions » de l'onglet Téléviseur affiche. Identique au compagnon.
 *
 * [lues] vaut `null` tant qu'on n'a rien demandé au téléviseur, et [paquetLu] retient à quel
 * paquet la lecture se rapportait : changer de paquet sans relire ne doit pas laisser croire
 * qu'on connaît l'état du nouveau.
 */
data class EtatPermissions(
    val paquet: String = "",
    val permission: String = PERMISSIONS_COURANTES.first(),
    val lecture: Boolean = false,
    val lues: PermissionsPaquet? = null,
    val paquetLu: String = "",
    val modeAppOp: String = "",
) {
    /** L'app-op qui double la permission choisie, s'il y en a un. */
    val appOp: String get() = APP_OPS_ASSOCIES[permission].orEmpty()

    /** Vrai quand [lues] décrit bien le paquet actuellement saisi. */
    val aJour: Boolean get() = lues != null && paquetLu == paquet

    val accordee: Boolean get() = aJour && lues?.estAccordee(permission) == true

    val declaree: Boolean get() = aJour && lues?.estDeclaree(permission) == true

    val paquetIntrouvable: Boolean get() = aJour && lues?.paquetTrouve == false

    val saisieComplete: Boolean get() = paquet.isNotBlank() && permission.isNotBlank()
}

/** Les callbacks de la carte, groupés. */
data class ActionsPermissions(
    val onPaquet: (String) -> Unit,
    val onPermission: (String) -> Unit,
    val onLire: () -> Unit,
    val onAccorder: () -> Unit,
    val onRetirer: () -> Unit,
)

/**
 * Les permissions qu'Android double d'un app-op, et l'op correspondant.
 *
 * Accorder `PACKAGE_USAGE_STATS` sans poser `GET_USAGE_STATS` donne un `pm grant` réussi et une
 * application qui ne voit toujours rien : les deux verrous doivent tomber ensemble.
 */
val APP_OPS_ASSOCIES = mapOf(
    "android.permission.PACKAGE_USAGE_STATS" to "GET_USAGE_STATS",
    "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    "android.permission.WRITE_SETTINGS" to "WRITE_SETTINGS",
)

/**
 * Les permissions qu'on vient réellement chercher ici : celles de niveau `development`, qu'une
 * application déclare mais qu'Android n'accorde que depuis une session ADB. Un raccourci de
 * saisie — le champ reste libre, et c'est le téléviseur qui tranche pour tout le reste.
 */
val PERMISSIONS_COURANTES = listOf(
    "android.permission.DUMP",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.READ_LOGS",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.BATTERY_STATS",
)
