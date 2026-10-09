package net.jolabs40.tvslim.journal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
enum class TypeAction { DESACTIVATION, REACTIVATION, REGLAGE, ACCUEIL, PERMISSION, APP_OP, INSTALLATION, COMMANDE, DESINSTALLATION }

@Serializable
data class ActionJournal(
    val horodatage: Long,
    val type: TypeAction,
    val cible: String,
    val libelle: String,
    val commandeAnnulation: String,
    val reussi: Boolean,
    val message: String = "",
)

/** Packages these actions leave disabled, in reverse order of application. */
fun List<ActionJournal>.paquetsDesactives(): List<String> {
    val etat = LinkedHashMap<String, Boolean>()
    filter { it.reussi }.forEach { action ->
        when (action.type) {
            TypeAction.DESACTIVATION -> etat[action.cible] = true
            TypeAction.REACTIVATION -> etat.remove(action.cible)
            else -> Unit
        }
    }
    return etat.keys.toList().reversed()
}

/** Component of the last home screen these actions set, if any. */
fun List<ActionJournal>.dernierAccueil(): String? =
    lastOrNull { it.reussi && it.type == TypeAction.ACCUEIL }?.cible

/**
 * Action journal, which is what makes the debloat reversible: each action is recorded with the exact
 * command that undoes it.
 *
 * One journal per TV: the companion keeps one for the TCL and another for the Shield.
 */
class JournalRepository(
    private val fichier: File,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serialiseur = ListSerializer(ActionJournal.serializer())
    private val verrou = Mutex()

    private val _actions = MutableStateFlow<List<ActionJournal>>(emptyList())
    val actions: StateFlow<List<ActionJournal>> = _actions.asStateFlow()

    suspend fun charger() = withContext(Dispatchers.IO) {
        verrou.withLock {
            _actions.value = if (fichier.exists()) {
                runCatching { json.decodeFromString(serialiseur, fichier.readText()) }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
    }

    suspend fun ajouter(action: ActionJournal) = ajouter(listOf(action))

    suspend fun ajouter(nouvelles: List<ActionJournal>) = withContext(Dispatchers.IO) {
        if (nouvelles.isEmpty()) return@withContext
        verrou.withLock {
            val fusion = _actions.value + nouvelles
            _actions.value = fusion
            ecrire(fusion)
        }
    }

    /** Packages currently disabled according to the journal, in reverse order of application. */
    fun paquetsADesactivationActive(): List<String> = _actions.value.paquetsDesactives()

    /** Undo commands for changed settings; the most recent one per setting wins. */
    fun annulationsDesReglages(): Map<String, String> {
        val restauration = LinkedHashMap<String, String>()
        _actions.value.filter { it.reussi && it.type == TypeAction.REGLAGE }.forEach { action ->
            restauration[action.cible] = action.commandeAnnulation
        }
        return restauration
    }

    /**
     * Commands that restore app permissions; as for settings, the most recent one per target wins.
     * App-ops are included: a permission paired with an app-op is only fully restored by resetting
     * both.
     */
    fun annulationsDesPermissions(): Map<String, String> {
        val concernees = setOf(TypeAction.PERMISSION, TypeAction.APP_OP)
        val restauration = LinkedHashMap<String, String>()
        _actions.value.filter { it.reussi && it.type in concernees }.forEach { action ->
            restauration[action.cible] = action.commandeAnnulation
        }
        return restauration
    }

    suspend fun vider() = withContext(Dispatchers.IO) {
        verrou.withLock {
            _actions.value = emptyList()
            ecrire(emptyList())
        }
    }

    /** Writes a readable Markdown report to [cible] and returns its path. */
    suspend fun exporterMarkdown(cible: File, entete: String): String = withContext(Dispatchers.IO) {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.FRANCE)
        val texte = buildString {
            appendLine("# TV Slim — journal d'intervention")
            appendLine()
            appendLine(entete)
            appendLine()
            appendLine("| Horodatage | Action | Cible | Résultat | Annulation |")
            appendLine("|---|---|---|---|---|")
            _actions.value.forEach { action ->
                val resultat = if (action.reussi) "OK" else "ÉCHEC : ${action.message}"
                // An installation has no undo command: it would be a `pm uninstall`.
                val annulation = action.commandeAnnulation.takeIf { it.isNotBlank() }?.let { "`$it`" } ?: "—"
                appendLine(
                    "| ${format.format(Date(action.horodatage))} | ${action.type} | " +
                        "`${action.cible}` | $resultat | $annulation |",
                )
            }
            appendLine()
            appendLine("## Tout annuler depuis un ordinateur")
            appendLine()
            appendLine("```bash")
            paquetsADesactivationActive().forEach { appendLine("adb shell pm enable $it") }
            annulationsDesReglages().values.forEach { appendLine("adb shell $it") }
            annulationsDesPermissions().values.forEach { appendLine("adb shell $it") }
            appendLine("```")
        }
        cible.parentFile?.mkdirs()
        cible.writeText(texte)
        cible.absolutePath
    }

    private fun ecrire(actions: List<ActionJournal>) {
        runCatching {
            fichier.parentFile?.mkdirs()
            fichier.writeText(json.encodeToString(serialiseur, actions))
        }
    }
}
