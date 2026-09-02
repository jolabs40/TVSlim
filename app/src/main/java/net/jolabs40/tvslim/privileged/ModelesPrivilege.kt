package net.jolabs40.tvslim.privileged

/** Résultat brut d'une commande exécutée dans le process privilégié. */
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
 * État de la passerelle privilégiée. L'application reste utilisable dans tous les cas :
 * sans privilèges, elle bascule en mode assistant et se contente de produire le script à
 * exécuter depuis un ordinateur.
 */
enum class EtatPrivilege {
    /** État initial, avant la première interrogation de Shizuku. */
    INCONNU,

    /** Shizuku n'est pas installé sur le téléviseur. */
    ABSENT,

    /** Shizuku est installé mais son service n'est pas démarré (à relancer après chaque redémarrage). */
    SERVICE_ARRETE,

    /** Le service tourne mais l'autorisation n'a pas encore été accordée à cette application. */
    AUTORISATION_REQUISE,

    /** L'autorisation a été refusée. */
    REFUSE,

    /** Tout est en place : les commandes privilégiées peuvent être exécutées. */
    PRET,
}
