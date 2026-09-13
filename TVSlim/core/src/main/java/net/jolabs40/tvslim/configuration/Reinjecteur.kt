package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction

/**
 * Réinjecte une configuration dans le seul ordre sûr, et toujours par le moteur : ses garde-fous
 * valent ici comme partout, et chaque action entre au journal avec la commande qui l'annule.
 *
 *  1. réactiver d'abord — rien n'est retiré avant que ce qui doit revenir soit revenu ;
 *  2. désactiver ensuite : liste noire, launcher tiers exigé, `setupwraith` avant `launcherx` ;
 *  3. l'écran d'accueil en dernier — `set-home-activity` n'a aucun effet tant que l'accueil d'usine
 *     est actif.
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
            // Sans composant connu pour l'accueil en place, l'annulation désignera le même : inoffensive.
            resultats += moteur.definirAccueil(
                composant = accueil.composant,
                ancienAccueil = infos.composantAccueil.ifBlank { accueil.composant },
            )
        }

        surProgression(total, total)
        return resultats
    }
}
