package net.jolabs40.tvslim.shell

/** Pourquoi une commande envoyée une seule fois n'a pas rendu de code de retour. */
enum class Interruption { DELAI, CONNEXION }

/** Ce qu'une commande tapée à la main a rendu — même coupée en route. */
data class ReponseDirecte(
    /** `null` quand la commande n'a pas fini : coupée par le délai, ou par la connexion. */
    val code: Int?,
    val sortie: String,
    val interruption: Interruption? = null,
    /** Le motif technique d'une interruption. */
    val motif: String = "",
)

/**
 * Exécution d'une commande que TV Slim n'a pas écrite.
 *
 * À part d'[ExecuteurCommande], dont les commandes sont rejouées après une rupture parce qu'elles
 * peuvent l'être : rien ne le dit d'une commande tapée à la main.
 */
interface ExecuteurDirect {
    /** Exécute [commande] une seule fois, et rend ce qu'elle a écrit avant une éventuelle coupure. */
    suspend fun executerUneFois(commande: String): ReponseDirecte
}
