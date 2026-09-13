package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil

/**
 * Ce que réinjecter une configuration changerait sur ce téléviseur — calculé avant d'y toucher, pour
 * être montré et confirmé. Seuls les écarts y figurent : un paquet déjà dans l'état voulu ne coûte rien.
 */
data class PlanReinjection(
    val configuration: ConfigurationTv,
    /** Actifs dans la sauvegarde, désactivés ici. */
    val aReactiver: List<EntreePaquet>,
    /** Désactivés dans la sauvegarde, actifs ici. */
    val aDesactiver: List<EntreePaquet>,
    /** Paquets de la sauvegarde absents de ce téléviseur, ou que le catalogue ne connaît plus. */
    val ignores: List<String>,
    /** L'écran d'accueil de la sauvegarde, quand il diffère de celui en place. */
    val accueil: ChangementAccueil?,
) {
    val nombreActions: Int
        get() = aReactiver.size + aDesactiver.size + if (accueil?.possible == true) 1 else 0

    val rienAFaire: Boolean get() = nombreActions == 0
}

/** L'écran d'accueil à rétablir. [composant] reste vide quand ce launcher manque sur ce téléviseur. */
data class ChangementAccueil(
    val paquet: String,
    val nom: String,
    val composant: String,
) {
    val possible: Boolean get() = composant.isNotBlank()
}

/** Compare la sauvegarde au téléviseur tel qu'il vient d'être lu, sans rien exécuter. */
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
        // La liste noire ne se rejoue jamais, même écrite dans un fichier : le moteur la refuserait,
        // autant ne pas la promettre dans l'aperçu.
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

    // Où le trouver ici : parmi les launchers installés, et les accueils d'usine désactivés compris —
    // ceux-là sont réactivés avant d'être désignés.
    val candidats = infos.launchersTiers.map { it.paquet to it.composant } +
        infos.accueilsUsine.map { it.paquet to it.composant }

    // Le même paquet d'abord, sinon une autre version du même launcher : la sauvegarde d'un téléviseur
    // de développement désigne la version .debug.
    val trouve = candidats.firstOrNull { it.first == voulu.paquet }
        ?: candidats.firstOrNull { catalogue.memeLauncher(voulu.paquet, it.first) }

    return ChangementAccueil(
        paquet = trouve?.first ?: voulu.paquet,
        nom = voulu.nom.ifBlank { catalogue.nomLauncher(voulu.paquet) ?: voulu.paquet },
        composant = trouve?.second.orEmpty(),
    )
}

/** Deux paquets du même launcher : identiques, ou versions connues d'une même application. */
private fun Catalogue.memeLauncher(a: String, b: String): Boolean =
    a == b ||
        launchers.any { it.correspond(a) && it.correspond(b) } ||
        launchersConnus.any { a in it.paquets && b in it.paquets }
