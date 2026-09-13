package net.jolabs40.tvslim.windows.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class DonneesPreferences(
    val dernierHote: String = "",
    val dernierPort: Int = PORT_ADB_PAR_DEFAUT,
    /** Nom de chaque appareil par adresse, appris à la première connexion. */
    val nomsConnus: Map<String, String> = emptyMap(),
    /** On peut refuser que l'application interroge GitHub au démarrage. */
    val verifierMisesAJour: Boolean = true,
)

/**
 * Préférences de l'application, dans un petit fichier JSON lisible.
 *
 * Même rôle que le DataStore du compagnon : retenir le dernier téléviseur joint et le nom de
 * chaque appareil, pour ne rien ressaisir. Chaque écriture passe par un fichier provisoire renommé
 * d'un coup : une coupure au mauvais moment laisse l'ancien fichier intact, jamais un demi-JSON.
 */
class PreferencesWindows(private val fichier: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val verrou = Mutex()
    private var cache: DonneesPreferences? = null

    suspend fun lire(): DonneesPreferences = withContext(Dispatchers.IO) {
        verrou.withLock { charger() }
    }

    suspend fun retenir(hote: String, port: Int) =
        modifier { it.copy(dernierHote = hote, dernierPort = port) }

    /**
     * Retient comment s'appelle l'appareil à cette adresse. Le service ADB ne publie qu'un numéro
     * de série ; une fois connecté, on connaît son modèle, autant s'en servir les fois suivantes.
     */
    suspend fun retenirNom(hote: String, nom: String) {
        if (hote.isBlank() || nom.isBlank()) return
        modifier { it.copy(nomsConnus = it.nomsConnus + (hote to nom)) }
    }

    suspend fun majVerificationMisesAJour(active: Boolean) =
        modifier { it.copy(verifierMisesAJour = active) }

    private suspend fun modifier(transformation: (DonneesPreferences) -> DonneesPreferences) {
        withContext(Dispatchers.IO) {
            verrou.withLock {
                val nouvelles = transformation(charger())
                // Retenues pour la session même si le disque refuse : on ne perd que la suivante.
                cache = nouvelles
                ecrire(nouvelles)
            }
        }
    }

    private fun charger(): DonneesPreferences = cache ?: lireFichier().also { cache = it }

    private fun lireFichier(): DonneesPreferences {
        if (!fichier.exists()) return DonneesPreferences()
        return runCatching {
            json.decodeFromString(DonneesPreferences.serializer(), fichier.readText(Charsets.UTF_8))
        }.getOrElse { erreur ->
            Traces.avertir(TAG, "Préférences illisibles, valeurs par défaut", erreur)
            DonneesPreferences()
        }
    }

    private fun ecrire(donnees: DonneesPreferences) {
        runCatching {
            fichier.parentFile?.mkdirs()
            val provisoire = File(fichier.parentFile, fichier.name + ".tmp")
            provisoire.writeText(json.encodeToString(DonneesPreferences.serializer(), donnees), Charsets.UTF_8)
            Files.move(
                provisoire.toPath(),
                fichier.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { Traces.avertir(TAG, "Préférences non enregistrées", it) }
    }

    private companion object {
        const val TAG = "Preferences"
    }
}
