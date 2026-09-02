package net.jolabs40.tvslim.system

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.magasin: DataStore<Preferences> by preferencesDataStore(name = "tvslim")

/** Préférences de l'application. Rien de sensible n'y est stocké. */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    /**
     * Gardien de démarrage : réapplique au boot les réglages que le téléviseur remet à leur
     * valeur d'usine à chaque redémarrage (`low_power_standby_enabled` au premier chef).
     */
    val gardienActif: Flow<Boolean> = contexte.magasin.data.map { it[CLE_GARDIEN] ?: false }

    suspend fun gardienActifMaintenant(): Boolean = gardienActif.first()

    suspend fun definirGardien(actif: Boolean) {
        contexte.magasin.edit { it[CLE_GARDIEN] = actif }
    }

    private companion object {
        val CLE_GARDIEN = booleanPreferencesKey("gardien_demarrage")
    }
}
