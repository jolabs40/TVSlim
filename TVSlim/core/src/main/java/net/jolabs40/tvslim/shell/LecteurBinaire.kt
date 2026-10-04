package net.jolabs40.tvslim.shell

/**
 * Ce qu'une commande à sortie binaire a rendu : sa sortie standard octet pour octet, à part de ce qu'elle a
 * écrit sur sa sortie d'erreur.
 */
class SortieBinaire(
    /** `null` quand la commande n'a pas fini : coupée par le délai, ou par la connexion. */
    val code: Int?,
    val octets: ByteArray,
    val erreurs: String = "",
    /** Le motif technique d'une interruption. */
    val motif: String = "",
)

/**
 * Lecture d'une commande dont la sortie n'est pas du texte — `screencap -p`, un PNG.
 *
 * À part d'[ExecuteurCommande], qui rend une chaîne : un PNG lu comme du texte UTF-8 n'en est plus un.
 * Rien n'est rejoué après une rupture : une capture manquée se redemande d'un clic.
 */
interface LecteurBinaire {
    suspend fun lireBinaire(commande: String): SortieBinaire
}
