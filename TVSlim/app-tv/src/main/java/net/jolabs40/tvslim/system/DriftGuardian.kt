package net.jolabs40.tvslim.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.MainActivity
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.device.DeviceRepository
import net.jolabs40.tvslim.device.BootDrift
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.BootSnapshot
import net.jolabs40.tvslim.device.driftSince
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects drift on the TV: on every boot, takes a snapshot (firmware, disabled catalogue packages, current
 * launcher) and compares it with the previous one. A system update that re-enabled packages or restored
 * the stock launcher leaves a report on the app's home screen, and a notification when allowed.
 *
 * Reports only, never repairs: disabling a package needs an ADB session, which only the phone and PC open.
 * They offer the fix from their own log (`driftPlan`).
 *
 * Read-only through `PackageManager`, no privileged permission needed.
 */
@Singleton
class DriftGuardian @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalogRepo: CatalogRepository,
    private val device: DeviceRepository,
    private val preferences: PreferencesRepository,
) {

    /** Snapshots this boot, compares it with the previous one and stores any drift. */
    suspend fun check(): BootDrift? = withContext(Dispatchers.IO) {
        val catalog = catalogRepo.catalog()
        val states = catalog.entries.map { it.packageName }.distinct().associateWith(device::state)
        val photo = BootSnapshot(
            fingerprint = Build.FINGERPRINT,
            disabled = states.filterValues { it == PackageState.DISABLED }.keys,
            home = device.currentHome(),
        )
        val drift = photo.driftSince(
            before = preferences.photo(),
            active = states.filterValues { it == PackageState.ACTIVE }.keys,
            factoryHomes = catalog.factoryHomes(),
        )
        preferences.rememberSnapshot(photo)
        // An older report stays until it is fixed; a boot without an update does not clear it.
        if (drift != null) {
            preferences.rememberDrift(drift)
            showNotification(drift, catalog)
        }
        drift
    }

    private fun showNotification(drift: BootDrift, catalog: Catalog) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.drift_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // A silhouette icon: Android would render the app icon as a white square.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.drift_title))
            .setContentText(summary(drift, catalog))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary(drift, catalog)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(ID_NOTIFICATION, notification)
    }

    /** One sentence per kind of drift, then what to do about it. */
    private fun summary(drift: BootDrift, catalog: Catalog): String = buildList {
        if (drift.reenabled.isNotEmpty()) {
            add(
                context.resources.getQuantityString(
                    R.plurals.drift_packages,
                    drift.reenabled.size,
                    drift.reenabled.size,
                ),
            )
        }
        drift.lostHome?.let { add(context.getString(R.string.drift_home, catalog.launcherNameOrPackage(it))) }
        add(context.getString(R.string.drift_fix))
    }.joinToString(" ")

    private companion object {
        const val CHANNEL_ID = "derive"
        const val ID_NOTIFICATION = 1
    }
}

/** Stock launchers known to the catalogue, which may only be disabled with a third-party launcher installed. */
fun Catalog.factoryHomes(): Set<String> = entries.filter { it.requiresThirdPartyLauncher }.map { it.packageName }.toSet()

/** Display name of a launcher, or its package name. */
fun Catalog.launcherNameOrPackage(packageName: String): String = launcherName(packageName) ?: packageName
