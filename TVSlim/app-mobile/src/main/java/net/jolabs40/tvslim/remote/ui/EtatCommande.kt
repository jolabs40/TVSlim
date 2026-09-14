package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.commande.EchangeCommande

/** Ce que la carte « Commande ADB » affiche. */
@Immutable
data class EtatCommande(
    val saisie: String = "",
    val enCours: Boolean = false,
    /** La dernière commande envoyée et sa sortie, qui restent à l'écran jusqu'à la suivante. */
    val derniere: EchangeCommande? = null,
    /** Les commandes envoyées, la plus récente d'abord, sans doublon : le menu du champ les propose. */
    val historique: List<String> = emptyList(),
)

/** Les callbacks de la carte, groupés comme ceux des permissions. */
data class ActionsCommande(
    val onSaisie: (String) -> Unit,
    val onEnvoyer: () -> Unit,
)

/** Retient une commande envoyée, en tête de l'historique. */
fun EtatCommande.avecEnvoi(commande: String): EtatCommande = copy(
    historique = (listOf(commande) + historique.filterNot { it == commande }).take(HISTORIQUE_MAX),
)

private const val HISTORIQUE_MAX = 20
