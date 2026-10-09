package net.jolabs40.tvslim.shell

/** Raw result of a command run on the TV. */
data class ResultatShell(
    val code: Int,
    val sortie: String,
) {
    val reussi: Boolean get() = code == 0

    companion object {
        fun indisponible(motif: String) = ResultatShell(code = -1, sortie = motif)
    }
}

/**
 * Privileged execution channel to a TV.
 *
 * The debloat engine only depends on this interface, so the transport (a local Shizuku service or an ADB
 * connection from a phone) can change without touching the catalogue, the safeguards or the journal.
 */
interface ExecuteurCommande {
    /** Runs a shell command and returns its exit code with stdout and stderr merged. */
    suspend fun executer(commande: String): ResultatShell
}
