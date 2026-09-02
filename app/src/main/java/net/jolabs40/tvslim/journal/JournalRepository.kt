package net.jolabs40.tvslim.journal

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
enum class TypeAction { DESACTIVATION, REACTIVATION, REGLAGE, ACCUEIL }

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

/**
 * Journal des interventions : c'est lui qui rend l'opération réversible. Chaque action y est
 * consignée avec la commande exacte qui l'annule, à l'image du journal Markdown tenu à la main
 * lors de la première intervention.
 */
@Singleton
class JournalRepository @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val verrou = Mutex()
    private val fichier: File get() = File(contexte.filesDir, FICHIER)

    private val _actions = MutableStateFlow<List<ActionJournal>>(emptyList())
    val actions: StateFlow<List<ActionJournal>> = _actions.asStateFlow()

    suspend fun charger() = withContext(Dispatchers.IO) {
        verrou.withLock {
            _actions.value = if (fichier.exists()) {
                runCatching {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ActionJournal.serializer()),
                        fichier.readText(),
                    )
                }.getOrDefault(emptyList())
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

    /** Paquets actuellement désactivés d'après le journal, dans l'ordre inverse d'application. */
    fun paquetsADesactivationActive(): List<String> {
        val etat = LinkedHashMap<String, Boolean>()
        _actions.value.filter { it.reussi }.forEach { action ->
            when (action.type) {
                TypeAction.DESACTIVATION -> etat[action.cible] = true
                TypeAction.REACTIVATION -> etat.remove(action.cible)
                else -> Unit
            }
        }
        return etat.keys.toList().reversed()
    }

    /** Réglages modifiés par l'application, avec la valeur d'origine à restaurer. */
    fun reglagesModifies(): Map<String, String> {
        val restauration = LinkedHashMap<String, String>()
        _actions.value.filter { it.reussi && it.type == TypeAction.REGLAGE }.forEach { action ->
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

    /** Écrit un rapport Markdown lisible et renvoie son chemin. */
    suspend fun exporterMarkdown(entete: String): String = withContext(Dispatchers.IO) {
        val dossier = contexte.getExternalFilesDir(null) ?: contexte.filesDir
        val cible = File(dossier, "TVSlim-journal.md")
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
                appendLine(
                    "| ${format.format(Date(action.horodatage))} | ${action.type} | " +
                        "`${action.cible}` | $resultat | `${action.commandeAnnulation}` |",
                )
            }
            appendLine()
            appendLine("## Tout annuler depuis un ordinateur")
            appendLine()
            appendLine("```bash")
            paquetsADesactivationActive().forEach { appendLine("adb shell pm enable $it") }
            appendLine("```")
        }
        cible.writeText(texte)
        cible.absolutePath
    }

    private fun ecrire(actions: List<ActionJournal>) {
        runCatching {
            fichier.writeText(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ActionJournal.serializer()),
                    actions,
                ),
            )
        }
    }

    private companion object {
        const val FICHIER = "journal.json"
    }
}
