package net.jolabs40.tvslim.remote.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.command.CommandExchange

/**
 * State of the "Shizuku" card. The last restart's output stays on screen: the starter prints the new pid,
 * and a failure is worth reading.
 */
@Immutable
data class ShizukuState(
    val inProgress: Boolean = false,
    val last: CommandExchange? = null,
)

data class ShizukuActions(
    val onRelaunch: () -> Unit,
)
