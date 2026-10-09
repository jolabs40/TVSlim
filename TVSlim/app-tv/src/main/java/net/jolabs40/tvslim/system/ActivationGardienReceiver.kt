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
 * `ApplicationTv`), so the guard runs without anyone opening the app.
 *
 * Only a sender holding `WRITE_SECURE_SETTINGS` may call it (`android:permission` in the manifest), i.e. the
 * ADB shell, not an app on the TV. Does what the settings switch does (guard, then first snapshot) and
 * replies [GARDIEN_ACTIVE].
 */
@AndroidEntryPoint
class ActivationGardienReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: PreferencesRepository

    @Inject lateinit var derive: GardienDerive

    override fun onReceive(contexte: Context, intention: Intent) {
        if (intention.action != ACTION) return
        val relais = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                preferences.definirGardien(true)
                // First snapshot right away, or the first system update would go unnoticed.
                runCatching { derive.verifier() }.onFailure { Log.w(TAG, "Première photo manquée", it) }
                relais.resultCode = GARDIEN_ACTIVE
                Log.i(TAG, "Gardien activé à distance")
            } finally {
                relais.finish()
            }
        }
    }

    companion object {
        /** Must match `ApplicationTv.ACTION_GARDIEN` and `GARDIEN_ACTIVE` in the core. */
        const val ACTION = "net.jolabs40.tvslim.action.ACTIVER_GARDIEN"
        const val GARDIEN_ACTIVE = 1
        private const val TAG = "TVSlim/Gardien"
    }
}
