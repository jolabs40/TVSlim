package net.jolabs40.tvslim.system

import android.content.Context
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.catalog.CatalogRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boot guard work, run once per boot whichever path triggers it: the boot broadcast ([BootReceiver])
 * where the manufacturer lets it through, otherwise [TrampolineActivity], which StartLight opens at power-on.
 *
 * On TCL only the second path works. Its boot manager (`TclAppBoot`) denies third-party apps the boot
 * broadcast, services and even notification listener binding, and resets any `AUTO_START` grant made over
 * ADB (per package or per uid) on every boot. Starting an activity is not filtered.
 *
 * First takes the boot snapshot ([DriftGuardian]), then reapplies settings the TV resets to factory values,
 * notably `low_power_standby_enabled`, which makes it unreachable in standby. Only the reapply step needs
 * `WRITE_SECURE_SETTINGS`.
 */
@Singleton
class BootGuardian @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesRepository,
    private val settings: SystemSettings,
    private val catalog: CatalogRepository,
    private val drift: DriftGuardian,
) {

    /** For a caller that finishes right away (an activity): the work outlives it. */
    fun start(trigger: String) {
        scope.launch { onBoot(trigger) }
    }

    suspend fun onBoot(trigger: String) {
        if (!preferences.guardianActiveNow()) return
        // Android's boot count tells boots apart. Two triggers may fire for one boot (broadcast and
        // StartLight, or StartLight relaunched); the work runs once.
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (bootCount >= 0 && !preferences.claimBoot(bootCount)) {
            Log.i(TAG, "Allumage $bootCount déjà traité ($trigger)")
            return
        }
        Log.i(TAG, "Gardien de l'allumage $bootCount, par $trigger")

        runCatching { drift.check() }
            .onSuccess { finding -> finding?.let { Log.i(TAG, "Dérive après mise à jour : $it") } }
            .onFailure { Log.w(TAG, "Dérive non vérifiée", it) }
        if (!settings.canWriteDirectly()) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS absente : réglages non réappliqués.")
            return
        }
        catalog.catalog().settings
            .filter { it.reapplyOnBoot }
            .forEach { setting ->
                val error = settings.write(setting, setting.optimizedValue)
                if (error == null) {
                    Log.i(TAG, "${setting.key} remis à ${setting.optimizedValue}")
                } else {
                    Log.w(TAG, "${setting.key} : $error")
                }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "TVSlim/Demarrage"
    }
}
