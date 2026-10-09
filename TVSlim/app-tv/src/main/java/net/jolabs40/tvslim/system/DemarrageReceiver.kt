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
 * Boot guard entry point, the main reason this app exists on the TV.
 *
 * Some settings reset to factory values on every reboot, notably `low_power_standby_enabled`, which makes
 * the device unreachable over the network in standby. The phone is not there at boot to fix it; this
 * receiver is, where the manufacturer lets BOOT_COMPLETED through. TCL filters it, so there StartLight
 * opens [PassageActivity] instead. [GardienDemarrage] runs once per boot whichever path triggers it.
 *
 * Relies on `WRITE_SECURE_SETTINGS`, granted once over ADB, which survives reboots. Also checks for drift
 * ([GardienDerive]).
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
