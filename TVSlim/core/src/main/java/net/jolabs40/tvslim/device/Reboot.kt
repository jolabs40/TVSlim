package net.jolabs40.tvslim.device

import kotlinx.coroutines.CancellationException
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.DirectExecutor

/**
 * Reboots the TV normally and nothing else: not `reboot recovery` or `reboot bootloader`, which lead to
 * factory reset, nor `reboot -p`, which would power it off out of network reach.
 *
 * Uses [DirectExecutor.executeOnce] because `execute` replays a command after a session drop, and the
 * session dies precisely with the reboot: a second `reboot` would go out once the TV is back.
 *
 * Logged to the journal as a command, with no undo: a new action type would make the journal unreadable to
 * older versions of the app.
 */
class Reboot(
    private val direct: DirectExecutor,
    private val journal: JournalRepository,
) {

    /** Sends the reboot. The answer hardly matters: the connection drops with the TV. */
    suspend fun reboot() {
        try {
            direct.executeOnce(COMMAND)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The session dropping mid-command is the expected outcome.
        }
        journal.add(
            JournalAction(
                timestamp = System.currentTimeMillis(),
                type = ActionType.COMMAND,
                target = COMMAND,
                label = LABEL,
                undoCommand = "",
                succeeded = true,
            ),
        )
    }

    companion object {
        const val COMMAND = "reboot"
        private const val LABEL = "Redémarrage du téléviseur"
    }
}
