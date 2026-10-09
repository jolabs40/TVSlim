package net.jolabs40.tvslim.remote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import net.jolabs40.tvslim.remote.adb.DEFAULT_ADB_PORT
import net.jolabs40.tvslim.support.SupportStore
import net.jolabs40.tvslim.support.SupportMemory
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "tvslim-remote")

/** Remembers the last TV address, known device names and the support banner state. */
@Singleton
class RemotePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) : SupportStore {

    suspend fun lastHost(): String = context.store.data.first()[KEY_HOST].orEmpty()

    suspend fun lastPort(): Int =
        context.store.data.first()[KEY_PORT] ?: DEFAULT_ADB_PORT

    /**
     * Remembers the device name at this address. The mDNS ADB service only publishes a serial number, but the
     * model is known after the first connection.
     */
    suspend fun rememberName(host: String, name: String) {
        if (host.isBlank() || name.isBlank()) return
        context.store.edit { it[stringPreferencesKey(NAME_PREFIX + host)] = name }
    }

    suspend fun knownNames(): Map<String, String> = context.store.data.first()
        .asMap()
        .filterKeys { it.name.startsWith(NAME_PREFIX) }
        .map { (key, rawValue) -> key.name.removePrefix(NAME_PREFIX) to rawValue.toString() }
        .toMap()

    suspend fun rememberAddress(host: String, port: Int) {
        context.store.edit {
            it[KEY_HOST] = host
            it[KEY_PORT] = port
        }
    }

    override suspend fun readSupport(): SupportMemory = context.store.data.first().let {
        SupportMemory(donationDeclared = it[KEY_DONATION] ?: false, lastInvitation = it[KEY_INVITATION] ?: 0L)
    }

    override suspend fun writeSupport(memory: SupportMemory) {
        context.store.edit {
            it[KEY_DONATION] = memory.donationDeclared
            it[KEY_INVITATION] = memory.lastInvitation
        }
    }

    private companion object {
        const val NAME_PREFIX = "nom_"
        val KEY_HOST = stringPreferencesKey("dernier_hote")
        val KEY_PORT = intPreferencesKey("dernier_port")
        val KEY_DONATION = booleanPreferencesKey("soutien_don_declare")
        val KEY_INVITATION = longPreferencesKey("soutien_derniere_invitation")
    }
}
