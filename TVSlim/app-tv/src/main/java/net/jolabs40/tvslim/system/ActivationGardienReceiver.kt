package net.jolabs40.tvslim.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Turns on the boot guard remotely. The phone or PC sends this right after installing the app (core's
 * `TvApp`), so the guard runs without anyone opening the app.
 *
 * Only a sender holding `WRITE_SECURE_SETTINGS` may call it (`android:permission` in the manifest), i.e. the
 * ADB shell, not an app on the TV. Does what the settings switch does (guard, then first snapshot) and
 * replies [GUARDIAN_ENABLED].
 *
 * Keeps its original (French) class name: `TvApp.GUARDIAN_COMMAND` targets it by name, and phones and PCs
 * send that command to TVs that may still run an older version of this app.
 */
@AndroidEntryPoint
class ActivationGardienReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: PreferencesRepository

    @Inject lateinit var drift: DriftGuardian

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                preferences.setGuardianEnabled(true)
                // First snapshot right away, or the first system update would go unnoticed.
                runCatching { drift.check() }.onFailure { Log.w(TAG, "Première photo manquée", it) }
                pendingResult.resultCode = GUARDIAN_ENABLED
                Log.i(TAG, "Gardien activé à distance")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        /** Must match `TvApp.ACTION_GUARDIAN` and `GUARDIAN_ENABLED` in the core. */
        const val ACTION = "net.jolabs40.tvslim.action.ACTIVER_GARDIEN"
        const val GUARDIAN_ENABLED = 1
        private const val TAG = "TVSlim/Gardien"
    }
}
