package net.jolabs40.tvslim.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gardien de démarrage — la raison d'être de cette application sur le téléviseur.
 *
 * Certains réglages reviennent à leur valeur d'usine à **chaque redémarrage** —
 * `low_power_standby_enabled` au premier chef, qui rend l'appareil injoignable en réseau
 * pendant la veille. Aucun compagnon mobile ne peut corriger cela : il n'est pas là au
 * démarrage. Ce récepteur, si — là où le fabricant laisse passer BOOT_COMPLETED. Sur une TCL, qui le
 * filtre, c'est StartLight qui prend le relais, par [PassageActivity] ; [GardienDemarrage] ne travaille
 * qu'une fois par allumage, quelle que soit la porte.
 *
 * Il repose sur `WRITE_SECURE_SETTINGS`, accordée une seule fois par ADB, qui survit aux
 * redémarrages. Il guette aussi la **dérive** — cf. [GardienDerive].
 */
@AndroidEntryPoint
class DemarrageReceiver : BroadcastReceiver() {

    @Inject lateinit var gardien: GardienDemarrage

    override fun onReceive(contexte: Context, intention: Intent) {
        if (intention.action != Intent.ACTION_BOOT_COMPLETED) return
        val relais = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                gardien.auDemarrage("BOOT_COMPLETED")
            } finally {
                relais.finish()
            }
        }
    }
}
