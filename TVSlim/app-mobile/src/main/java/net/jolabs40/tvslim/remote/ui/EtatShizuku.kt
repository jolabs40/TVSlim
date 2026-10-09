package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.commande.EchangeCommande

/**
 * State of the "Shizuku" card. The last restart's output stays on screen: the starter prints the new pid,
 * and a failure is worth reading.
 */
@Immutable
data class EtatShizuku(
    val enCours: Boolean = false,
    val derniere: EchangeCommande? = null,
)

data class ActionsShizuku(
    val onRelancer: () -> Unit,
)
