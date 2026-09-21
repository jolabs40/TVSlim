package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.commande.EchangeCommande

/**
 * Ce que la carte « Shizuku » affiche.
 *
 * Le bilan de la dernière relance reste à l'écran : la sortie du starter dit le
 * pid obtenu, et un refus mérite d'être relu.
 */
@Immutable
data class EtatShizuku(
    val enCours: Boolean = false,
    val derniere: EchangeCommande? = null,
)

/** Le callback de la carte, groupé comme ceux des autres. */
data class ActionsShizuku(
    val onRelancer: () -> Unit,
)
