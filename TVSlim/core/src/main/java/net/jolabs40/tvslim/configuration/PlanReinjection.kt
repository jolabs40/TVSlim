package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil

/**
 * What reapplying a configuration would change on this TV, computed before touching it so it can be
 * shown and confirmed. Only differences are listed.
 */
data class PlanReinjection(
    val configuration: ConfigurationTv,
    /** Enabled in the backup, disabled here. */
    val aReactiver: List<EntreePaquet>,
    /** Disabled in the backup, enabled here. */
    val aDesactiver: List<EntreePaquet>,
    /** Backup packages missing from this TV, or no longer in the catalogue. */
    val ignores: List<String>,
    /** Home screen from the backup, when it differs from the current one. */
    val accueil: ChangementAccueil?,
) {
    val nombreActions: Int
        get() = aReactiver.size + aDesactiver.size + if (accueil?.possible == true) 1 else 0

    val rienAFaire: Boolean get() = nombreActions == 0
}

/** Home screen to restore. [composant] is blank when that launcher is missing on this TV. */
data class ChangementAccueil(
    val paquet: String,
    val nom: String,
    val composant: String,
) {
    val possible: Boolean get() = composant.isNotBlank()
}

/** Compares the backup with the TV as just read, without running anything. */
fun ConfigurationTv.planifier(
    catalogue: Catalogue,
    etats: Map<String, EtatPaquet>,
    infos: InfosAppareil,
): PlanReinjection {
    val entrees = catalogue.entrees.associateBy { it.paquet }
    fun etat(paquet: String) = etats[paquet] ?: EtatPaquet.ABSENT

    return PlanReinjection(
        configuration = this,
        aReactiver = actifs.distinct().mapNotNull { entrees[it] }
            .filter { etat(it.paquet) == EtatPaquet.DESACTIVE },
        // Blocklisted packages are never replayed, even from a file: the engine would refuse them, so the
        // preview does not list them.
        aDesactiver = desactives.distinct().mapNotNull { entrees[it] }
            .filter { etat(it.paquet) == EtatPaquet.ACTIF && !catalogue.estProtege(it.paquet) },
        ignores = (actifs + desactives).distinct()
            .filter { it !in entrees || etat(it) == EtatPaquet.ABSENT },
        accueil = changementAccueil(catalogue, infos),
    )
}

private fun ConfigurationTv.changementAccueil(catalogue: Catalogue, infos: InfosAppareil): ChangementAccueil? {
    val voulu = accueil ?: return null
    if (catalogue.memeLauncher(voulu.paquet, infos.accueilActuel)) return null

    // Candidates: installed launchers and stock home screens, disabled ones included (those are
    // re-enabled before being set).
    val candidats = infos.launchersTiers.map { it.paquet to it.composant } +
        infos.accueilsUsine.map { it.paquet to it.composant }

    // Same package first, otherwise another variant of the same launcher: a backup from a development TV
    // names the .debug build.
    val trouve = candidats.firstOrNull { it.first == voulu.paquet }
        ?: candidats.firstOrNull { catalogue.memeLauncher(voulu.paquet, it.first) }

    return ChangementAccueil(
        paquet = trouve?.first ?: voulu.paquet,
        nom = voulu.nom.ifBlank { catalogue.nomLauncher(voulu.paquet) ?: voulu.paquet },
        composant = trouve?.second.orEmpty(),
    )
}

/** True if both packages belong to the same launcher: identical, or known variants of one app. */
private fun Catalogue.memeLauncher(a: String, b: String): Boolean =
    a == b ||
        launchers.any { it.correspond(a) && it.correspond(b) } ||
        launchersConnus.any { a in it.paquets && b in it.paquets }
