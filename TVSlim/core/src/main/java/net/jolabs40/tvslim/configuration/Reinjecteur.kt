package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction

/**
 * Reapplies a configuration in the only safe order, always through the engine, so its safeguards
 * apply and every action is journaled with its undo command.
 *
 *  1. Re-enable first: nothing is removed before what must come back is back.
 *  2. Then disable: blocklist, third-party launcher required, `setupwraith` before `launcherx`.
 *  3. Home screen last: `set-home-activity` has no effect while the stock home screen is enabled.
 */
class Reinjecteur(private val moteur: MoteurDebloat) {

    suspend fun reinjecter(
        plan: PlanReinjection,
        catalogue: Catalogue,
        etats: Map<String, EtatPaquet>,
        infos: InfosAppareil,
        surProgression: (fait: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ResultatAction> {
        val total = plan.nombreActions
        val resultats = mutableListOf<ResultatAction>()
        surProgression(0, total)

        if (plan.aReactiver.isNotEmpty()) {
            resultats += moteur.reactiver(plan.aReactiver.map { it.paquet }) { fait, _ ->
                surProgression(fait, total)
            }
        }

        if (plan.aDesactiver.isNotEmpty()) {
            val dejaFaits = plan.aReactiver.size
            resultats += moteur.desactiver(
                entrees = plan.aDesactiver,
                catalogue = catalogue,
                etats = etats + plan.aReactiver.associate { it.paquet to EtatPaquet.ACTIF },
                launchersDisponibles = infos.launchersTiers.isNotEmpty(),
                surProgression = { fait, _ -> surProgression(dejaFaits + fait, total) },
            )
        }

        plan.accueil?.takeIf { it.possible }?.let { accueil ->
            // If the current home component is unknown, undo sets the same one again, which is harmless.
            resultats += moteur.definirAccueil(
                composant = accueil.composant,
                ancienAccueil = infos.composantAccueil.ifBlank { accueil.composant },
            )
        }

        surProgression(total, total)
        return resultats
    }
}
