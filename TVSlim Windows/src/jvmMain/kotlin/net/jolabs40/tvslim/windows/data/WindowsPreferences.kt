package net.jolabs40.tvslim.windows.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jolabs40.tvslim.support.SupportStore
import net.jolabs40.tvslim.support.SupportMemory
import net.jolabs40.tvslim.windows.adb.DEFAULT_ADB_PORT
import net.jolabs40.tvslim.windows.tools.AppLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class PreferencesData(
    @SerialName("dernierHote") val lastHost: String = "",
    @SerialName("dernierPort") val lastPort: Int = DEFAULT_ADB_PORT,
    /** Device name per address, learned on first connection. */
    @SerialName("nomsConnus") val knownNames: Map<String, String> = emptyMap(),
    /** Whether to query GitHub for updates at startup. */
    @SerialName("verifierMisesAJour") val checkForUpdates: Boolean = true,
    /** "I already donated": the support banner no longer shows. */
    @SerialName("donDeclare") val donationDeclared: Boolean = false,
    /** Last time the support banner was shown; 0 if never. */
    @SerialName("derniereInvitationSoutien") val lastSupportInvitation: Long = 0L,
)

/**
 * App preferences in a small readable JSON file (the companion uses DataStore).
 *
 * Writes go to a temporary file then an atomic rename, so a crash leaves the old file intact, never half a JSON.
 */
class WindowsPreferences(private val file: File) : SupportStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val lock = Mutex()
    private var cache: PreferencesData? = null

    suspend fun read(): PreferencesData = withContext(Dispatchers.IO) {
        lock.withLock { load() }
    }

    suspend fun rememberAddress(host: String, port: Int) =
        modifier { it.copy(lastHost = host, lastPort = port) }

    /** Remembers the device name for this address, since the ADB mDNS service only advertises a serial number. */
    suspend fun rememberName(host: String, name: String) {
        if (host.isBlank() || name.isBlank()) return
        modifier { it.copy(knownNames = it.knownNames + (host to name)) }
    }

    suspend fun setUpdateCheck(active: Boolean) =
        modifier { it.copy(checkForUpdates = active) }

    override suspend fun readSupport(): SupportMemory =
        read().let { SupportMemory(donationDeclared = it.donationDeclared, lastInvitation = it.lastSupportInvitation) }

    override suspend fun writeSupport(memory: SupportMemory) =
        modifier { it.copy(donationDeclared = memory.donationDeclared, lastSupportInvitation = memory.lastInvitation) }

    private suspend fun modifier(transformation: (PreferencesData) -> PreferencesData) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val fresh = transformation(load())
                // Kept in memory even if the write fails; only the next session loses them.
                cache = fresh
                write(fresh)
            }
        }
    }

    private fun load(): PreferencesData = cache ?: readFile().also { cache = it }

    private fun readFile(): PreferencesData {
        if (!file.exists()) return PreferencesData()
        return runCatching {
            json.decodeFromString(PreferencesData.serializer(), file.readText(Charsets.UTF_8))
        }.getOrElse { error ->
            AppLog.warn(TAG, "Preferences unreadable, using defaults", error)
            PreferencesData()
        }
    }

    private fun write(data: PreferencesData) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(json.encodeToString(PreferencesData.serializer(), data), Charsets.UTF_8)
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { AppLog.warn(TAG, "Preferences not saved", it) }
    }

    private companion object {
        const val TAG = "Preferences"
    }
}
