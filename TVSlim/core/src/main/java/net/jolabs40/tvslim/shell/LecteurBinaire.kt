package net.jolabs40.tvslim.shell

/** Output of a binary command: stdout byte for byte, stderr kept apart. */
class SortieBinaire(
    /** `null` when the command did not finish (timeout or lost connection). */
    val code: Int?,
    val octets: ByteArray,
    val erreurs: String = "",
    /** Technical reason for the interruption. */
    val motif: String = "",
)

/**
 * Runs a command whose output is not text, such as `screencap -p` (a PNG).
 *
 * Separate from [ExecuteurCommande], which returns a string: a PNG decoded as UTF-8 is corrupted.
 * Nothing is replayed after a disconnect; a missed capture is one click away.
 */
interface LecteurBinaire {
    suspend fun lireBinaire(commande: String): SortieBinaire
}
