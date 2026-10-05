package net.jolabs40.tvslim.windows.ui

import net.jolabs40.tvslim.moteur.MotifMoteur
import net.jolabs40.tvslim.moteur.NatureNom
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.engine_absent
import net.jolabs40.tvslim.windows.ressources.engine_already_disabled
import net.jolabs40.tvslim.windows.ressources.engine_invalid_appop
import net.jolabs40.tvslim.windows.ressources.engine_invalid_component
import net.jolabs40.tvslim.windows.ressources.engine_invalid_package
import net.jolabs40.tvslim.windows.ressources.engine_invalid_permission
import net.jolabs40.tvslim.windows.ressources.engine_no_activity
import net.jolabs40.tvslim.windows.ressources.engine_no_launcher
import net.jolabs40.tvslim.windows.ressources.engine_not_user_installed
import net.jolabs40.tvslim.windows.ressources.engine_permission_not_requested
import net.jolabs40.tvslim.windows.ressources.engine_protected
import net.jolabs40.tvslim.windows.ressources.engine_unexplained
import net.jolabs40.tvslim.windows.ressources.engine_unknown_appop_mode

/**
 * Ce qu'une action a donné, à rédiger à l'affichage : le motif du moteur s'il en a un, sinon ce que le
 * téléviseur a répondu, tel quel.
 */
fun ResultatAction.texte(): MessageUi = motif?.message() ?: MessageUi.Brut(message)

/** Un [MotifMoteur] en phrase des ressources : le noyau, partagé avec Android, n'écrit aucune phrase. */
fun MotifMoteur.message(): MessageUi = when (this) {
    MotifMoteur.PaquetAbsent -> texte(Res.string.engine_absent)
    MotifMoteur.DejaDesactive -> texte(Res.string.engine_already_disabled)
    MotifMoteur.EchecInexplique -> texte(Res.string.engine_unexplained)
    MotifMoteur.SansLauncherTiers -> texte(Res.string.engine_no_launcher)
    MotifMoteur.AucuneActivite -> texte(Res.string.engine_no_activity)
    MotifMoteur.PasInstalleeParLaPersonne -> texte(Res.string.engine_not_user_installed)
    is MotifMoteur.Protege -> texte(Res.string.engine_protected, raison)
    is MotifMoteur.ModeAppOpInconnu -> texte(Res.string.engine_unknown_appop_mode, mode)
    is MotifMoteur.PermissionNonDemandee -> texte(Res.string.engine_permission_not_requested, paquet, permission)
    is MotifMoteur.NomInvalide -> texte(
        when (nature) {
            NatureNom.PAQUET -> Res.string.engine_invalid_package
            NatureNom.PERMISSION -> Res.string.engine_invalid_permission
            NatureNom.APP_OP -> Res.string.engine_invalid_appop
            NatureNom.COMPOSANT -> Res.string.engine_invalid_component
        },
        valeur,
    )
}
