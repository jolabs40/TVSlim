package net.jolabs40.tvslim.system

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.jolabs40.tvslim.device.BootDrift
import net.jolabs40.tvslim.device.BootSnapshot
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "tvslim")

/** App preferences. Nothing sensitive is stored here. */
@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Boot guard switch: reapplies settings the TV resets on every reboot (`low_power_standby_enabled`). */
    val guardianActive: Flow<Boolean> = context.store.data.map { it[KEY_GUARDIAN] ?: false }

    suspend fun guardianActiveNow(): Boolean = guardianActive.first()

    suspend fun setGuardianEnabled(active: Boolean) {
        context.store.edit { it[KEY_GUARDIAN] = active }
    }

    /** Snapshot of the last boot ([BootSnapshot]), null before the first one. */
    suspend fun photo(): BootSnapshot? = context.store.data.first().let { data ->
        val fingerprint = data[KEY_FINGERPRINT] ?: return@let null
        BootSnapshot(
            fingerprint = fingerprint,
            disabled = data[KEY_DISABLED].orEmpty(),
            home = data[KEY_HOME].orEmpty(),
        )
    }

    suspend fun rememberSnapshot(photo: BootSnapshot) {
        context.store.edit {
            it[KEY_FINGERPRINT] = photo.fingerprint
            it[KEY_DISABLED] = photo.disabled
            it[KEY_HOME] = photo.home
        }
    }

    /**
     * Claims boot [number] (`Settings.Global.BOOT_COUNT`). Returns true for the first caller only, so the
     * guard runs once per boot whichever path triggers it.
     */
    suspend fun claimBoot(number: Int): Boolean {
        var free = false
        context.store.edit {
            free = it[KEY_BOOT] != number
            it[KEY_BOOT] = number
        }
        return free
    }

    /** What the last system update undid, until it is fixed. */
    val drift: Flow<BootDrift?> = context.store.data.map { data ->
        BootDrift(
            reenabled = data[KEY_REENABLED].orEmpty().sorted(),
            lostHome = data[KEY_HOME_LOST],
        ).takeUnless { it.empty }
    }

    suspend fun rememberDrift(drift: BootDrift?) {
        context.store.edit {
            if (drift == null || drift.empty) {
                it.remove(KEY_REENABLED)
                it.remove(KEY_HOME_LOST)
            } else {
                it[KEY_REENABLED] = drift.reenabled.toSet()
                drift.lostHome?.let { lost -> it[KEY_HOME_LOST] = lost } ?: it.remove(KEY_HOME_LOST)
            }
        }
    }

    private companion object {
        val KEY_GUARDIAN = booleanPreferencesKey("gardien_demarrage")
        val KEY_BOOT = intPreferencesKey("allumage_traite")
        val KEY_FINGERPRINT = stringPreferencesKey("photo_empreinte")
        val KEY_DISABLED = stringSetPreferencesKey("photo_desactives")
        val KEY_HOME = stringPreferencesKey("photo_accueil")
        val KEY_REENABLED = stringSetPreferencesKey("derive_rallumes")
        val KEY_HOME_LOST = stringPreferencesKey("derive_accueil_perdu")
    }
}
