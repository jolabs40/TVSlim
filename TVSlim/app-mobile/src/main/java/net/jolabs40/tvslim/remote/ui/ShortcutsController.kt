package net.jolabs40.tvslim.remote.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.remote.data.RemotePreferences
import net.jolabs40.tvslim.remote.data.RemoteShortcut
import net.jolabs40.tvslim.remote.data.RemoteShortcuts

data class ShortcutsUi(
    /** Address of the TV they belong to; null before the first connection, which hides them. */
    val host: String? = null,
    val shortcuts: List<RemoteShortcut> = emptyList(),
)

/** The shortcuts of the remote's main screen, for the TV last connected. */
class ShortcutsController(
    private val preferences: RemotePreferences,
    private val scope: CoroutineScope,
    host: Flow<String>,
) {

    private val _state = MutableStateFlow(ShortcutsUi())
    val state: StateFlow<ShortcutsUi> = _state.asStateFlow()

    init {
        // Kept through a disconnection: they stay in place, greyed, while the remote reconnects.
        scope.launch {
            host.filter { it.isNotBlank() }.distinctUntilChanged().collect { address ->
                _state.value = ShortcutsUi(address, preferences.shortcuts(address))
            }
        }
    }

    fun toggle(shortcut: RemoteShortcut) = change { RemoteShortcuts.toggle(it, shortcut) }

    fun move(index: Int, by: Int) = change { RemoteShortcuts.move(it, index, by) }

    fun remove(index: Int) = change { shortcuts -> shortcuts.filterIndexed { i, _ -> i != index } }

    private fun change(block: (List<RemoteShortcut>) -> List<RemoteShortcut>) {
        val current = _state.value
        val host = current.host ?: return
        val updated = block(current.shortcuts)
        _state.value = current.copy(shortcuts = updated)
        scope.launch {
            // The latest list rather than this change's: quick changes in a row cannot be saved out of order.
            val latest = _state.value
            preferences.saveShortcuts(host, if (latest.host == host) latest.shortcuts else updated)
        }
    }
}
