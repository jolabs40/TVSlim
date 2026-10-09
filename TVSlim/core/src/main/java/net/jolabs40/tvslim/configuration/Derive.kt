package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.dernierAccueil
import net.jolabs40.tvslim.journal.paquetsDesactives

/**
 * Drift: what TV Slim disabled on this TV that came back without it, usually after a system update
 * re-enabled packages and restored the stock home screen.
 *
 * The desired state comes from the journal (packages it leaves disabled, last home screen set).
 * Compared with the TV as just read, it yields an ordinary [PlanReinjection], confirmed and applied
 * like a backup. A package re-enabled by hand from the TV settings is listed too; the user can decline.
 *
 * Only a fallback to the stock home screen counts as home drift: another launcher picked since then
 * is a deliberate choice and must not be offered again on every connection.
 *
 * @return null when the TV is as TV Slim left it.
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
    // Missing or unknown packages have not drifted; a backup plan lists them, this one does not.
    return plan.copy(accueil = accueil, ignores = emptyList()).takeUnless { it.rienAFaire }
}

/**
 * True if the home screen fell back to the stock one, or to Android's chooser (shown when two home
 * screens compete). A blank value is a failed read, not drift.
 */
private fun InfosAppareil.accueilRetombe(): Boolean =
    accueilActuel == "android" || accueilsUsine.any { it.paquet == accueilActuel }
