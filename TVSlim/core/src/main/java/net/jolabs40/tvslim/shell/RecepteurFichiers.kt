package net.jolabs40.tvslim.shell

import java.io.OutputStream

/**
 * Reads a file from the TV like `adb pull`, over the ADB sync protocol ([EnvoyeurFichiers] in reverse).
 *
 * Separate from [ExecuteurCommande] for the same reason: a multi-gigabyte copy must never be replayed behind the
 * user's back. Only the Windows connection implements it.
 */
interface RecepteurFichiers {
    /**
     * Writes the contents of [chemin] to [destination], which the caller closes. [taille] is only for progress
     * (0 when unknown). [annule] is checked on every block.
     *
     * Returns code 0 when everything arrived, code 1 with the TV's own message when it refused
     * (`open failed: Permission denied`), or [ResultatShell.indisponible] when the connection dropped or the
     * copy was cancelled, in which case [destination] holds only part of the file.
     */
    suspend fun recevoir(
        chemin: String,
        destination: OutputStream,
        taille: Long,
        annule: () -> Boolean,
        surRecu: (recu: Long) -> Unit,
    ): ResultatShell
}
