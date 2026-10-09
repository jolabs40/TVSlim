package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.commande.EchangeCommande

/** State of the "ADB command" card. */
@Immutable
data class EtatCommande(
    val saisie: String = "",
    val enCours: Boolean = false,
    /** Last command and its output, shown until the next one. */
    val derniere: EchangeCommande? = null,
    /** Sent commands, most recent first, without duplicates; offered in the field's menu. */
    val historique: List<String> = emptyList(),
)

data class ActionsCommande(
    val onSaisie: (String) -> Unit,
    val onEnvoyer: () -> Unit,
)

/** Moves a sent command to the top of the history. */
fun EtatCommande.avecEnvoi(commande: String): EtatCommande = copy(
    historique = (listOf(commande) + historique.filterNot { it == commande }).take(HISTORIQUE_MAX),
)

private const val HISTORIQUE_MAX = 20
