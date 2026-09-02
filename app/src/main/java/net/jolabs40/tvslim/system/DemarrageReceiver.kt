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
import net.jolabs40.tvslim.catalog.CatalogueRepository
import javax.inject.Inject

/**
 * Gardien de démarrage.
 *
 * Certains réglages reviennent à leur valeur d'usine à chaque redémarrage du téléviseur —
 * `low_power_standby_enabled` au premier chef, qui rend l'appareil injoignable en réseau
 * pendant la veille. Ce récepteur les réapplique tout seul.
 *
 * Il n'utilise pas Shizuku : son service n'est pas démarré à cet instant. Il repose sur
 * l'autorisation `WRITE_SECURE_SETTINGS`, accordée une seule fois par ADB, qui elle survit
 * aux redémarrages.
 */
@AndroidEntryPoint
class DemarrageReceiver : BroadcastReceiver() {

    @Inject lateinit var preferences: PreferencesRepository

    @Inject lateinit var reglages: ReglagesSysteme

    @Inject lateinit var catalogue: CatalogueRepository

    override fun onReceive(contexte: Context, intention: Intent) {
        if (intention.action != Intent.ACTION_BOOT_COMPLETED) return
        val relais = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (!preferences.gardienActifMaintenant()) return@launch
                if (!reglages.ecritureDirectePossible()) {
                    Log.w(TAG, "WRITE_SECURE_SETTINGS absente : réglages non réappliqués.")
                    return@launch
                }
                catalogue.catalogue().reglages
                    .filter { it.reappliquerAuDemarrage }
                    .forEach { reglage ->
                        val erreur = reglages.ecrire(reglage, reglage.valeurOptimisee)
                        if (erreur == null) {
                            Log.i(TAG, "${reglage.cle} remis à ${reglage.valeurOptimisee}")
                        } else {
                            Log.w(TAG, "${reglage.cle} : $erreur")
                        }
                    }
            } finally {
                relais.finish()
            }
        }
    }

    private companion object {
        const val TAG = "TVSlim/Demarrage"
    }
}
