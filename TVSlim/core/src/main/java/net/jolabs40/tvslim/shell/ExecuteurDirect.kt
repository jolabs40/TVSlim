package net.jolabs40.tvslim.shell

/** Why a run-once command returned no exit code. */
enum class Interruption { DELAI, CONNEXION }

/** Output of a hand-typed command, even if it was cut off. */
data class ReponseDirecte(
    /** `null` when the command did not finish (timeout or lost connection). */
    val code: Int?,
    val sortie: String,
    val interruption: Interruption? = null,
    /** Technical reason for the interruption. */
    val motif: String = "",
)

/**
 * Runs a command that TV Slim did not write.
 *
 * Separate from [ExecuteurCommande], which replays its commands after a disconnect because they are known
 * to be safe to repeat. Nothing says that of a hand-typed command.
 */
interface ExecuteurDirect {
    /** Runs [commande] exactly once and returns whatever it printed before any interruption. */
    suspend fun executerUneFois(commande: String): ReponseDirecte
}
