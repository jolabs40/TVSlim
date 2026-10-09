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
 * opens [TrampolineActivity] instead. [BootGuardian] runs once per boot whichever path triggers it.
 *
 * Relies on `WRITE_SECURE_SETTINGS`, granted once over ADB, which survives reboots. Also checks for drift
 * ([DriftGuardian]).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var guardian: BootGuardian

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                guardian.onBoot("BOOT_COMPLETED")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
