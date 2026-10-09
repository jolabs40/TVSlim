package net.jolabs40.tvslim.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
enum class ActionType { @SerialName("DESACTIVATION") DISABLING, @SerialName("REACTIVATION") ENABLING, @SerialName("REGLAGE") SETTING, @SerialName("ACCUEIL") HOME, PERMISSION, APP_OP, INSTALLATION, @SerialName("COMMANDE") COMMAND, @SerialName("DESINSTALLATION") UNINSTALLATION }

@Serializable
data class JournalAction(
    @SerialName("horodatage") val timestamp: Long,
    val type: ActionType,
    @SerialName("cible") val target: String,
    @SerialName("libelle") val label: String,
    @SerialName("commandeAnnulation") val undoCommand: String,
    @SerialName("reussi") val succeeded: Boolean,
    val message: String = "",
)

/** Packages these actions leave disabled, in reverse order of application. */
fun List<JournalAction>.disabledPackages(): List<String> {
    val state = LinkedHashMap<String, Boolean>()
    filter { it.succeeded }.forEach { action ->
        when (action.type) {
            ActionType.DISABLING -> state[action.target] = true
            ActionType.ENABLING -> state.remove(action.target)
            else -> Unit
        }
    }
    return state.keys.toList().reversed()
}

/** Component of the last home screen these actions set, if any. */
fun List<JournalAction>.lastHome(): String? =
    lastOrNull { it.succeeded && it.type == ActionType.HOME }?.target

/**
 * Action journal, which is what makes the debloat reversible: each action is recorded with the exact
 * command that undoes it.
 *
 * One journal per TV: the companion keeps one for the TCL and another for the Shield.
 */
class JournalRepository(
    private val file: File,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(JournalAction.serializer())
    private val lock = Mutex()

    private val _actions = MutableStateFlow<List<JournalAction>>(emptyList())
    val actions: StateFlow<List<JournalAction>> = _actions.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            _actions.value = if (file.exists()) {
                runCatching { json.decodeFromString(serializer, file.readText()) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
    }

    suspend fun add(action: JournalAction) = add(listOf(action))

    suspend fun add(fresh: List<JournalAction>) = withContext(Dispatchers.IO) {
        if (fresh.isEmpty()) return@withContext
        lock.withLock {
            val merged = _actions.value + fresh
            _actions.value = merged
            write(merged)
        }
    }

    /** Packages currently disabled according to the journal, in reverse order of application. */
    fun activelyDisabledPackages(): List<String> = _actions.value.disabledPackages()

    /** Undo commands for changed settings; the most recent one per setting wins. */
    fun settingUndos(): Map<String, String> {
        val restoreCommands = LinkedHashMap<String, String>()
        _actions.value.filter { it.succeeded && it.type == ActionType.SETTING }.forEach { action ->
            restoreCommands[action.target] = action.undoCommand
        }
        return restoreCommands
    }

    /**
     * Commands that restore app permissions; as for settings, the most recent one per target wins.
     * App-ops are included: a permission paired with an app-op is only fully restored by resetting
     * both.
     */
    fun permissionUndos(): Map<String, String> {
        val relevant = setOf(ActionType.PERMISSION, ActionType.APP_OP)
        val restoreCommands = LinkedHashMap<String, String>()
        _actions.value.filter { it.succeeded && it.type in relevant }.forEach { action ->
            restoreCommands[action.target] = action.undoCommand
        }
        return restoreCommands
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            _actions.value = emptyList()
            write(emptyList())
        }
    }

    /** Writes a readable Markdown report to [target] and returns its path. */
    suspend fun exportMarkdown(target: File, header: String): String = withContext(Dispatchers.IO) {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE)
        val text = buildString {
            appendLine("# TV Slim — journal d'intervention")
            appendLine()
            appendLine(header)
            appendLine()
            appendLine("| Horodatage | Action | Cible | Résultat | Annulation |")
            appendLine("|---|---|---|---|---|")
            _actions.value.forEach { action ->
                val result = if (action.succeeded) "OK" else "ÉCHEC : ${action.message}"
                // An installation has no undo command: it would be a `pm uninstall`.
                val cancellation = action.undoCommand.takeIf { it.isNotBlank() }?.let { "`$it`" } ?: "—"
                appendLine(
                    "| ${format.format(Date(action.timestamp))} | ${action.type} | " +
                        "`${action.target}` | $result | $cancellation |",
                )
            }
            appendLine()
            appendLine("## Tout annuler depuis un ordinateur")
            appendLine()
            appendLine("```bash")
            activelyDisabledPackages().forEach { appendLine("adb shell pm enable $it") }
            settingUndos().values.forEach { appendLine("adb shell $it") }
            permissionUndos().values.forEach { appendLine("adb shell $it") }
            appendLine("```")
        }
        target.parentFile?.mkdirs()
        target.writeText(text)
        target.absolutePath
    }

    private fun write(actions: List<JournalAction>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, actions))
        }
    }
}
