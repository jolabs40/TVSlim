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
 * Allume le gardien de démarrage à distance : c'est ce que fait le téléphone ou le PC juste après avoir
 * installé cette application (`ApplicationTv` du noyau), pour qu'elle ne dorme pas jusqu'à ce que
 * quelqu'un pense à l'ouvrir.
 *
 * Seul un expéditeur qui détient `WRITE_SECURE_SETTINGS` peut l'appeler (`android:permission` du
 * manifeste) : le shell d'ADB, pas une application du téléviseur. Il fait ce que fait l'interrupteur des
 * Réglages — le gardien, puis la première photo —, et répond [GARDIEN_ACTIVE] pour que l'appelant le
 * sache.
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
                // La première photo, tout de suite : sans elle, la première mise à jour passerait inaperçue.
                runCatching { derive.verifier() }.onFailure { Log.w(TAG, "Première photo manquée", it) }
                relais.resultCode = GARDIEN_ACTIVE
                Log.i(TAG, "Gardien activé à distance")
            } finally {
                relais.finish()
            }
        }
    }

    companion object {
        /** Mêmes valeurs que `ApplicationTv.ACTION_GARDIEN` et `GARDIEN_ACTIVE`, dans le noyau. */
        const val ACTION = "net.jolabs40.tvslim.action.ACTIVER_GARDIEN"
        const val GARDIEN_ACTIVE = 1
        private const val TAG = "TVSlim/Gardien"
    }
}
