package net.jolabs40.tvslim.remote.ui

import android.content.Context
import net.jolabs40.tvslim.moteur.MotifMoteur
import net.jolabs40.tvslim.moteur.NatureNom
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.remote.R

/** Describes an action's outcome: the engine's reason from resources if any, otherwise the TV's raw output. */
fun ResultatAction.texte(contexte: Context): String = motif?.rediger(contexte) ?: message

/** Localizes a [MotifMoteur]; the core, shared with Windows, contains no user-facing text. */
fun MotifMoteur.rediger(contexte: Context): String = when (this) {
    MotifMoteur.PaquetAbsent -> contexte.getString(R.string.engine_absent)
    MotifMoteur.DejaDesactive -> contexte.getString(R.string.engine_already_disabled)
    MotifMoteur.EchecInexplique -> contexte.getString(R.string.engine_unexplained)
    MotifMoteur.SansLauncherTiers -> contexte.getString(R.string.engine_no_launcher)
    MotifMoteur.AucuneActivite -> contexte.getString(R.string.engine_no_activity)
    MotifMoteur.PasInstalleeParLaPersonne -> contexte.getString(R.string.engine_not_user_installed)
    is MotifMoteur.Protege -> contexte.getString(R.string.engine_protected, raison)
    is MotifMoteur.ModeAppOpInconnu -> contexte.getString(R.string.engine_unknown_appop_mode, mode)
    is MotifMoteur.PermissionNonDemandee -> contexte.getString(R.string.engine_permission_not_requested, paquet, permission)
    is MotifMoteur.NomInvalide -> contexte.getString(
        when (nature) {
            NatureNom.PAQUET -> R.string.engine_invalid_package
            NatureNom.PERMISSION -> R.string.engine_invalid_permission
            NatureNom.APP_OP -> R.string.engine_invalid_appop
            NatureNom.COMPOSANT -> R.string.engine_invalid_component
        },
        valeur,
    )
}
