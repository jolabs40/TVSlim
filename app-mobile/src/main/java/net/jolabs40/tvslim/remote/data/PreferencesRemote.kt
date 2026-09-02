package net.jolabs40.tvslim.remote.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import net.jolabs40.tvslim.remote.adb.PORT_ADB_PAR_DEFAUT
import javax.inject.Inject
import javax.inject.Singleton

private val Context.magasin: DataStore<Preferences> by preferencesDataStore(name = "tvslim-remote")

/** Dernier téléviseur joint, pour ne pas ressaisir son adresse à chaque fois. */
@Singleton
class PreferencesRemote @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    suspend fun dernierHote(): String = contexte.magasin.data.first()[CLE_HOTE].orEmpty()

    suspend fun dernierPort(): Int =
        contexte.magasin.data.first()[CLE_PORT] ?: PORT_ADB_PAR_DEFAUT

    suspend fun retenir(hote: String, port: Int) {
        contexte.magasin.edit {
            it[CLE_HOTE] = hote
            it[CLE_PORT] = port
        }
    }

    private companion object {
        val CLE_HOTE = stringPreferencesKey("dernier_hote")
        val CLE_PORT = intPreferencesKey("dernier_port")
    }
}
