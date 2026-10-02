package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.dernierAccueil
import net.jolabs40.tvslim.journal.paquetsDesactives

/**
 * La dérive : ce que TV Slim a coupé sur ce téléviseur et qui s'est rallumé sans lui — une mise à jour
 * système, le plus souvent, qui réactive des paquets et rend la main à l'accueil d'usine.
 *
 * L'état voulu se lit dans le **journal** : les paquets qu'il laisse désactivés, et le dernier écran
 * d'accueil qu'il a posé. Comparé au téléviseur tel qu'il vient d'être lu, il donne un
 * [PlanReinjection] ordinaire — même confirmation, même [Reinjecteur], mêmes garde-fous qu'une
 * sauvegarde qu'on réinjecte.
 *
 * Ce n'est qu'une proposition : un paquet rallumé à la main, depuis les réglages du téléviseur, y
 * figure aussi, et la personne le laisse alors de côté en refusant.
 *
 * ⚠️ **Seul le retour à l'accueil d'usine compte comme une dérive de l'accueil.** Un autre launcher
 * choisi depuis — installé puis désigné à la télécommande — est un choix, pas un accident : le
 * reproposer à chaque connexion serait du harcèlement.
 *
 * @return null quand le téléviseur est tel que TV Slim l'a laissé.
 */
fun Catalogue.planDeDerive(
    journal: List<ActionJournal>,
    etats: Map<String, EtatPaquet>,
    infos: InfosAppareil,
): PlanReinjection? {
    val accueilPose = journal.dernierAccueil()
    val voulu = ConfigurationTv(
        application = ConfigurationTv.APPLICATION,
        format = ConfigurationTv.FORMAT,
        sauvegardeLe = 0,
        accueil = accueilPose?.let { AccueilSauvegarde(paquet = it.substringBefore('/'), composant = it) },
        desactives = journal.paquetsDesactives(),
    )
    val plan = voulu.planifier(this, etats, infos)
    val accueil = plan.accueil?.takeIf { it.possible && infos.accueilRetombe() }
    // Les paquets absents ou inconnus n'ont pas « dérivé » : le plan d'une sauvegarde les montre, pas celui-ci.
    return plan.copy(accueil = accueil, ignores = emptyList()).takeUnless { it.rienAFaire }
}

/**
 * L'accueil en place est-il retombé sur celui d'usine — ou sur le sélecteur d'Android, que montre un
 * téléviseur où deux accueils se disputent la place ? Un accueil vide est une lecture ratée, pas une
 * dérive.
 */
private fun InfosAppareil.accueilRetombe(): Boolean =
    accueilActuel == "android" || accueilsUsine.any { it.paquet == accueilActuel }
