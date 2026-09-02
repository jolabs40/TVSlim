package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.privileged.ShizukuPasserelle
import javax.inject.Inject
import javax.inject.Singleton

data class ResultatAction(
    val paquet: String,
    val nom: String,
    val reussi: Boolean,
    val message: String = "",
)

/**
 * Applique et annule les désactivations, avec les garde-fous appris lors de l'intervention
 * manuelle sur la TCL :
 *
 *  - jamais de `pm uninstall` : uniquement `pm disable-user --user 0`, réversible par `pm enable` ;
 *  - refus catégorique des paquets de la liste noire du catalogue ;
 *  - refus de toucher à l'écran d'accueil d'usine tant qu'aucun launcher tiers n'est installé ;
 *  - respect de l'ordre déclaré, pour que `setupwraith` tombe avant `launcherx` — sans quoi
 *    la RecoveryActivity de priorité 1 prendrait la main à la place du launcher choisi.
 */
@Singleton
class MoteurDebloat @Inject constructor(
    private val passerelle: ShizukuPasserelle,
    private val appareil: AppareilRepository,
    private val journal: JournalRepository,
) {

    suspend fun desactiver(
        entrees: List<EntreePaquet>,
        catalogue: Catalogue,
    ): List<ResultatAction> {
        val paquetsDAccueil = catalogue.entrees
            .filter { it.requiertLauncherTiers }
            .map { it.paquet }
            .toSet()
        val launchersDisponibles = appareil.launchersTiers(paquetsDAccueil).isNotEmpty()

        val resultats = mutableListOf<ResultatAction>()
        val aJournaliser = mutableListOf<ActionJournal>()

        entrees.sortedBy { it.ordre }.forEach { entree ->
            val refus = motifDeRefus(entree, catalogue, launchersDisponibles)
            if (refus != null) {
                resultats += ResultatAction(entree.paquet, entree.nom, false, refus)
                return@forEach
            }
            when (appareil.etat(entree.paquet)) {
                EtatPaquet.ABSENT -> {
                    resultats += ResultatAction(
                        entree.paquet, entree.nom, false, "Paquet absent de ce téléviseur.",
                    )
                    return@forEach
                }

                EtatPaquet.DESACTIVE -> {
                    resultats += ResultatAction(entree.paquet, entree.nom, true, "Déjà désactivé.")
                    return@forEach
                }

                EtatPaquet.ACTIF -> Unit
            }

            val sortie = passerelle.executer("pm disable-user --user 0 ${entree.paquet}")
            val reussi = sortie.reussi &&
                (sortie.sortie.contains("disabled-user") ||
                    appareil.etat(entree.paquet) == EtatPaquet.DESACTIVE)
            val message = if (reussi) "" else sortie.sortie.ifBlank { "Échec inexpliqué." }

            resultats += ResultatAction(entree.paquet, entree.nom, reussi, message)
            aJournaliser += ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.DESACTIVATION,
                cible = entree.paquet,
                libelle = entree.nom,
                commandeAnnulation = "pm enable ${entree.paquet}",
                reussi = reussi,
                message = message,
            )
        }

        journal.ajouter(aJournaliser)
        return resultats
    }

    suspend fun reactiver(paquets: List<String>): List<ResultatAction> {
        val resultats = mutableListOf<ResultatAction>()
        val aJournaliser = mutableListOf<ActionJournal>()

        paquets.forEach { paquet ->
            val sortie = passerelle.executer("pm enable $paquet")
            val reussi = sortie.reussi &&
                (sortie.sortie.contains("enabled") || appareil.etat(paquet) == EtatPaquet.ACTIF)
            val message = if (reussi) "" else sortie.sortie.ifBlank { "Échec inexpliqué." }

            resultats += ResultatAction(paquet, paquet, reussi, message)
            aJournaliser += ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.REACTIVATION,
                cible = paquet,
                libelle = paquet,
                commandeAnnulation = "pm disable-user --user 0 $paquet",
                reussi = reussi,
                message = message,
            )
        }

        journal.ajouter(aJournaliser)
        return resultats
    }

    /**
     * Désigne un écran d'accueil. À n'appeler qu'une fois l'accueil d'usine désactivé : tant
     * qu'il est actif, la commande répond `Success` sans le moindre effet.
     */
    suspend fun definirAccueil(composant: String, ancienAccueil: String): ResultatAction {
        val sortie = passerelle.executer("cmd package set-home-activity $composant")
        val reussi = sortie.reussi
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.ACCUEIL,
                cible = composant,
                libelle = "Écran d'accueil",
                commandeAnnulation = "cmd package set-home-activity $ancienAccueil",
                reussi = reussi,
                message = if (reussi) "" else sortie.sortie,
            ),
        )
        return ResultatAction(composant, "Écran d'accueil", reussi, sortie.sortie)
    }

    private fun motifDeRefus(
        entree: EntreePaquet,
        catalogue: Catalogue,
        launchersDisponibles: Boolean,
    ): String? = when {
        catalogue.estProtege(entree.paquet) ->
            "Paquet protégé : ${catalogue.motifProtection(entree.paquet)}"

        entree.requiertLauncherTiers && !launchersDisponibles ->
            "Aucun launcher tiers installé : le téléviseur démarrerait sur un écran vide."

        else -> null
    }
}
