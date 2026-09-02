package net.jolabs40.tvslim.shell

/** Résultat brut d'une commande exécutée sur le téléviseur. */
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
 * Canal d'exécution privilégiée vers un téléviseur.
 *
 * Le moteur de débloat ne connaît que cette interface : il ignore si les commandes passent par
 * un service local (Shizuku) ou par une connexion ADB depuis un téléphone. Changer de canal ne
 * touche donc ni au catalogue, ni aux garde-fous, ni au journal.
 */
interface ExecuteurCommande {
    /** Exécute une commande shell et renvoie son code de sortie avec sa sortie fusionnée. */
    suspend fun executer(commande: String): ResultatShell
}
