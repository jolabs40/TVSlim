package net.jolabs40.tvslim.system

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.jolabs40.tvslim.device.DeriveDemarrage
import net.jolabs40.tvslim.device.PhotoDemarrage
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

    /** La photo du dernier allumage — cf. [PhotoDemarrage]. Null avant le premier. */
    suspend fun photo(): PhotoDemarrage? = contexte.magasin.data.first().let { donnees ->
        val empreinte = donnees[CLE_EMPREINTE] ?: return@let null
        PhotoDemarrage(
            empreinte = empreinte,
            desactives = donnees[CLE_DESACTIVES].orEmpty(),
            accueil = donnees[CLE_ACCUEIL].orEmpty(),
        )
    }

    suspend fun retenirPhoto(photo: PhotoDemarrage) {
        contexte.magasin.edit {
            it[CLE_EMPREINTE] = photo.empreinte
            it[CLE_DESACTIVES] = photo.desactives
            it[CLE_ACCUEIL] = photo.accueil
        }
    }

    /** Ce que la dernière mise à jour système a défait, tant que rien ne l'a repris. */
    val derive: Flow<DeriveDemarrage?> = contexte.magasin.data.map { donnees ->
        DeriveDemarrage(
            rallumes = donnees[CLE_RALLUMES].orEmpty().sorted(),
            accueilPerdu = donnees[CLE_ACCUEIL_PERDU],
        ).takeUnless { it.vide }
    }

    suspend fun retenirDerive(derive: DeriveDemarrage?) {
        contexte.magasin.edit {
            if (derive == null || derive.vide) {
                it.remove(CLE_RALLUMES)
                it.remove(CLE_ACCUEIL_PERDU)
            } else {
                it[CLE_RALLUMES] = derive.rallumes.toSet()
                derive.accueilPerdu?.let { perdu -> it[CLE_ACCUEIL_PERDU] = perdu } ?: it.remove(CLE_ACCUEIL_PERDU)
            }
        }
    }

    private companion object {
        val CLE_GARDIEN = booleanPreferencesKey("gardien_demarrage")
        val CLE_EMPREINTE = stringPreferencesKey("photo_empreinte")
        val CLE_DESACTIVES = stringSetPreferencesKey("photo_desactives")
        val CLE_ACCUEIL = stringPreferencesKey("photo_accueil")
        val CLE_RALLUMES = stringSetPreferencesKey("derive_rallumes")
        val CLE_ACCUEIL_PERDU = stringPreferencesKey("derive_accueil_perdu")
    }
}
