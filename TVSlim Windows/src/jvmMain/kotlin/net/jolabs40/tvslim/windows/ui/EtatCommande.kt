package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.commande.EchangeCommande

/** Ce que la carte « Commande ADB » affiche. */
@Immutable
data class EtatCommande(
    val saisie: String = "",
    val enCours: Boolean = false,
    /** La dernière commande envoyée et sa sortie, qui restent à l'écran jusqu'à la suivante. */
    val derniere: EchangeCommande? = null,
    /** Les commandes envoyées, la plus récente d'abord, sans doublon. */
    val historique: List<String> = emptyList(),
    /** Le rang rappelé par ↑ dans [historique] ; -1 tant qu'on tape. */
    val rappel: Int = -1,
)

/** Les callbacks de la carte, groupés comme ceux des permissions. */
data class ActionsCommande(
    val onSaisie: (String) -> Unit,
    val onEnvoyer: () -> Unit,
    val onRappel: (plusAncienne: Boolean) -> Unit,
)

/**
 * ↑ remonte vers les commandes plus anciennes, ↓ redescend ; passé la plus récente, le champ se vide,
 * comme dans un terminal. ↓ sans rien avoir rappelé ne touche pas à ce qu'on tape.
 */
fun EtatCommande.avecRappel(plusAncienne: Boolean): EtatCommande {
    if (historique.isEmpty() || (!plusAncienne && rappel < 0)) return this
    val rang = if (plusAncienne) minOf(rappel + 1, historique.lastIndex) else rappel - 1
    return if (rang < 0) copy(saisie = "", rappel = -1) else copy(saisie = historique[rang], rappel = rang)
}

/** Retient une commande envoyée, en tête de l'historique. */
fun EtatCommande.avecEnvoi(commande: String): EtatCommande = copy(
    historique = (listOf(commande) + historique.filterNot { it == commande }).take(HISTORIQUE_MAX),
    rappel = -1,
)

private const val HISTORIQUE_MAX = 20
