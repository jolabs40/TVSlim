package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.commande.EchangeCommande

/** State of the "ADB command" card. */
@Immutable
data class EtatCommande(
    val saisie: String = "",
    val enCours: Boolean = false,
    /** Last command and its output, kept on screen until the next one. */
    val derniere: EchangeCommande? = null,
    /** Sent commands, most recent first, without duplicates. */
    val historique: List<String> = emptyList(),
    /** Index in [historique] recalled with Up; -1 while typing. */
    val rappel: Int = -1,
)

data class ActionsCommande(
    val onSaisie: (String) -> Unit,
    val onEnvoyer: () -> Unit,
    val onRappel: (plusAncienne: Boolean) -> Unit,
)

/**
 * Shell-style history: Up goes to older commands, Down to newer ones and clears the field past the most
 * recent. Down does nothing to the current input if nothing was recalled.
 */
fun EtatCommande.avecRappel(plusAncienne: Boolean): EtatCommande {
    if (historique.isEmpty() || (!plusAncienne && rappel < 0)) return this
    val rang = if (plusAncienne) minOf(rappel + 1, historique.lastIndex) else rappel - 1
    return if (rang < 0) copy(saisie = "", rappel = -1) else copy(saisie = historique[rang], rappel = rang)
}

/** Puts a sent command at the top of the history. */
fun EtatCommande.avecEnvoi(commande: String): EtatCommande = copy(
    historique = (listOf(commande) + historique.filterNot { it == commande }).take(HISTORIQUE_MAX),
    rappel = -1,
)

private const val HISTORIQUE_MAX = 20
