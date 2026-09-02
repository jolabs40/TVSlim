package net.jolabs40.tvslim.moteur

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurCommande

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
 *  - jamais de `pm uninstall` : uniquement `pm disable-user --user 0`, annulé par `pm enable` ;
 *  - refus catégorique des paquets de la liste noire du catalogue ;
 *  - refus de toucher à l'écran d'accueil d'usine tant qu'aucun launcher tiers n'est installé ;
 *  - respect de l'ordre déclaré, pour que `setupwraith` tombe avant `launcherx` — sans quoi
 *    la RecoveryActivity de priorité 1 prendrait la main à la place du launcher choisi.
 *
 * Le moteur ignore par quel canal les commandes partent : service local ou connexion ADB.
 */
class MoteurDebloat(
    private val executeur: ExecuteurCommande,
    private val journal: JournalRepository,
) {

    suspend fun desactiver(
        entrees: List<EntreePaquet>,
        catalogue: Catalogue,
        etats: Map<String, EtatPaquet>,
        launchersDisponibles: Boolean,
        surProgression: (fait: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ResultatAction> {
        val resultats = mutableListOf<ResultatAction>()
        val aJournaliser = mutableListOf<ActionJournal>()

        val aTraiter = entrees.sortedBy { it.ordre }
        aTraiter.forEachIndexed { rang, entree ->
            surProgression(rang, aTraiter.size)
            val refus = motifDeRefus(entree, catalogue, launchersDisponibles)
            if (refus != null) {
                resultats += ResultatAction(entree.paquet, entree.nom, false, refus)
                return@forEachIndexed
            }
            when (etats[entree.paquet] ?: EtatPaquet.ABSENT) {
                EtatPaquet.ABSENT -> {
                    resultats += ResultatAction(
                        entree.paquet, entree.nom, false, "Paquet absent de ce téléviseur.",
                    )
                    return@forEachIndexed
                }

                EtatPaquet.DESACTIVE -> {
                    resultats += ResultatAction(entree.paquet, entree.nom, true, "Déjà désactivé.")
                    return@forEachIndexed
                }

                EtatPaquet.ACTIF -> Unit
            }

            val sortie = executeur.executer("pm disable-user --user 0 ${entree.paquet}")
            val reussi = sortie.reussi && sortie.sortie.contains("disabled-user")
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

        surProgression(aTraiter.size, aTraiter.size)
        journal.ajouter(aJournaliser)
        return resultats
    }

    suspend fun reactiver(
        paquets: List<String>,
        surProgression: (fait: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ResultatAction> {
        val resultats = mutableListOf<ResultatAction>()
        val aJournaliser = mutableListOf<ActionJournal>()

        paquets.forEachIndexed { rang, paquet ->
            surProgression(rang, paquets.size)
            val sortie = executeur.executer("pm enable $paquet")
            val reussi = sortie.reussi && sortie.sortie.contains("enabled")
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

        surProgression(paquets.size, paquets.size)
        journal.ajouter(aJournaliser)
        return resultats
    }

    /**
     * Désigne un écran d'accueil. À n'appeler qu'une fois l'accueil d'usine désactivé : tant
     * qu'il est actif, la commande répond `Success` sans le moindre effet.
     */
    suspend fun definirAccueil(composant: String, ancienAccueil: String): ResultatAction {
        val sortie = executeur.executer("cmd package set-home-activity $composant")
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.ACCUEIL,
                cible = composant,
                libelle = "Écran d'accueil",
                commandeAnnulation = "cmd package set-home-activity $ancienAccueil",
                reussi = sortie.reussi,
                message = if (sortie.reussi) "" else sortie.sortie,
            ),
        )
        return ResultatAction(composant, "Écran d'accueil", sortie.reussi, sortie.sortie)
    }

    /**
     * Ouvre la fiche d'une application dans la boutique **du téléviseur**, à charge pour la
     * personne devant l'écran de valider l'installation à la télécommande.
     *
     * C'est volontairement tout ce que fait l'application : elle ne télécharge aucun APK et
     * n'installe rien d'elle-même. Le seul chemin passe par la boutique officielle.
     */
    suspend fun ouvrirFicheBoutique(paquet: String): ResultatAction {
        val sortie = executeur.executer(
            "am start -a android.intent.action.VIEW -d market://details?id=$paquet",
        )
        return ResultatAction(paquet, paquet, sortie.reussi, sortie.sortie)
    }

    /**
     * Arrête les processus d'une application. Rien à journaliser : ce n'est pas un changement
     * d'état mais une remise à zéro — l'application repart dès qu'on l'ouvre, ou dès qu'un
     * service la rappelle.
     */
    suspend fun forcerArret(paquet: String): ResultatAction {
        val sortie = executeur.executer("am force-stop $paquet")
        return ResultatAction(paquet, paquet, sortie.reussi, sortie.sortie)
    }

    /** Applique une valeur de réglage système et journalise son annulation. */
    suspend fun ecrireReglage(
        cle: String,
        portee: String,
        nom: String,
        valeur: String,
        valeurPrecedente: String,
    ): ResultatAction {
        val sortie = executeur.executer("settings put $portee $cle $valeur")
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.REGLAGE,
                cible = cle,
                libelle = nom,
                commandeAnnulation = "settings put $portee $cle $valeurPrecedente",
                reussi = sortie.reussi,
                message = if (sortie.reussi) "" else sortie.sortie,
            ),
        )
        return ResultatAction(cle, nom, sortie.reussi, sortie.sortie)
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
