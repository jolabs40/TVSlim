package net.jolabs40.tvslim.device

import kotlinx.coroutines.CancellationException
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurDirect

/**
 * Reboots the TV normally and nothing else: not `reboot recovery` or `reboot bootloader`, which lead to
 * factory reset, nor `reboot -p`, which would power it off out of network reach.
 *
 * Uses [ExecuteurDirect.executerUneFois] because `executer` replays a command after a session drop, and the
 * session dies precisely with the reboot: a second `reboot` would go out once the TV is back.
 *
 * Logged to the journal as a command, with no undo: a new action type would make the journal unreadable to
 * older versions of the app.
 */
class Redemarrage(
    private val direct: ExecuteurDirect,
    private val journal: JournalRepository,
) {

    /** Sends the reboot. The answer hardly matters: the connection drops with the TV. */
    suspend fun redemarrer() {
        try {
            direct.executerUneFois(COMMANDE)
        } catch (annulation: CancellationException) {
            throw annulation
        } catch (_: Exception) {
            // The session dropping mid-command is the expected outcome.
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
