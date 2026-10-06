package net.jolabs40.tvslim.device

import kotlinx.coroutines.CancellationException
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurDirect

/**
 * Redémarre le téléviseur — un redémarrage normal, et rien d'autre : ni `reboot recovery` ni
 * `reboot bootloader`, qui ouvrent sur la remise à zéro d'usine, ni `reboot -p`, qui l'éteindrait hors de
 * portée du réseau.
 *
 * Par [ExecuteurDirect.executerUneFois] : `executer` rejoue une commande après une rupture de session, et
 * la session meurt justement avec le redémarrage — un second `reboot` partirait au retour du téléviseur.
 *
 * Consigné au journal comme une commande (sans annulation, il n'y en a pas) : un nouveau type d'action
 * rendrait le journal illisible aux versions précédentes de l'application.
 */
class Redemarrage(
    private val direct: ExecuteurDirect,
    private val journal: JournalRepository,
) {

    /** Envoie l'ordre. La réponse importe peu : la connexion tombe avec le téléviseur. */
    suspend fun redemarrer() {
        try {
            direct.executerUneFois(COMMANDE)
        } catch (annulation: CancellationException) {
            throw annulation
        } catch (_: Exception) {
            // La session coupée en pleine commande est l'issue attendue.
        }
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.COMMANDE,
                cible = COMMANDE,
                libelle = LIBELLE,
                commandeAnnulation = "",
                reussi = true,
            ),
        )
    }

    companion object {
        const val COMMANDE = "reboot"
        private const val LIBELLE = "Redémarrage du téléviseur"
    }
}
