package net.jolabs40.tvslim.windows.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.soutien.MagasinSoutien
import net.jolabs40.tvslim.soutien.MemoireSoutien
import net.jolabs40.tvslim.windows.adb.PORT_ADB_PAR_DEFAUT
import net.jolabs40.tvslim.windows.outils.Traces
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class DonneesPreferences(
    val dernierHote: String = "",
    val dernierPort: Int = PORT_ADB_PAR_DEFAUT,
    /** Device name per address, learned on first connection. */
    val nomsConnus: Map<String, String> = emptyMap(),
    /** Whether to query GitHub for updates at startup. */
    val verifierMisesAJour: Boolean = true,
    /** "I already donated": the support banner no longer shows. */
    val donDeclare: Boolean = false,
    /** Last time the support banner was shown; 0 if never. */
    val derniereInvitationSoutien: Long = 0L,
)

/**
 * App preferences in a small readable JSON file (the companion uses DataStore).
 *
 * Writes go to a temporary file then an atomic rename, so a crash leaves the old file intact, never half a JSON.
 */
class PreferencesWindows(private val fichier: File) : MagasinSoutien {

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

    /** Remembers the device name for this address, since the ADB mDNS service only advertises a serial number. */
    suspend fun retenirNom(hote: String, nom: String) {
        if (hote.isBlank() || nom.isBlank()) return
        modifier { it.copy(nomsConnus = it.nomsConnus + (hote to nom)) }
    }

    suspend fun majVerificationMisesAJour(active: Boolean) =
        modifier { it.copy(verifierMisesAJour = active) }

    override suspend fun lireSoutien(): MemoireSoutien =
        lire().let { MemoireSoutien(donDeclare = it.donDeclare, derniereInvitation = it.derniereInvitationSoutien) }

    override suspend fun ecrireSoutien(memoire: MemoireSoutien) =
        modifier { it.copy(donDeclare = memoire.donDeclare, derniereInvitationSoutien = memoire.derniereInvitation) }

    private suspend fun modifier(transformation: (DonneesPreferences) -> DonneesPreferences) {
        withContext(Dispatchers.IO) {
            verrou.withLock {
                val nouvelles = transformation(charger())
                // Kept in memory even if the write fails; only the next session loses them.
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
