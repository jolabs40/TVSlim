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
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.CatalogueRepository
import net.jolabs40.tvslim.device.AppareilRepository
import net.jolabs40.tvslim.device.DeriveDemarrage
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.PhotoDemarrage
import net.jolabs40.tvslim.device.deriveDepuis
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects drift on the TV: on every boot, takes a snapshot (firmware, disabled catalogue packages, current
 * launcher) and compares it with the previous one. A system update that re-enabled packages or restored
 * the stock launcher leaves a report on the app's home screen, and a notification when allowed.
 *
 * Reports only, never repairs: disabling a package needs an ADB session, which only the phone and PC open.
 * They offer the fix from their own log (`planDeDerive`).
 *
 * Read-only through `PackageManager`, no privileged permission needed.
 */
@Singleton
class GardienDerive @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val catalogueRepo: CatalogueRepository,
    private val appareil: AppareilRepository,
    private val preferences: PreferencesRepository,
) {

    /** Snapshots this boot, compares it with the previous one and stores any drift. */
    suspend fun verifier(): DeriveDemarrage? = withContext(Dispatchers.IO) {
        val catalogue = catalogueRepo.catalogue()
        val etats = catalogue.entrees.map { it.paquet }.distinct().associateWith(appareil::etat)
        val photo = PhotoDemarrage(
            empreinte = Build.FINGERPRINT,
            desactives = etats.filterValues { it == EtatPaquet.DESACTIVE }.keys,
            accueil = appareil.accueilActuel(),
        )
        val derive = photo.deriveDepuis(
            avant = preferences.photo(),
            actifs = etats.filterValues { it == EtatPaquet.ACTIF }.keys,
            accueilsUsine = catalogue.accueilsUsine(),
        )
        preferences.retenirPhoto(photo)
        // An older report stays until it is fixed; a boot without an update does not clear it.
        if (derive != null) {
            preferences.retenirDerive(derive)
            notifier(derive, catalogue)
        }
        derive
    }

    private fun notifier(derive: DeriveDemarrage, catalogue: Catalogue) {
        val permise = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            contexte.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permise) return

        val gestionnaire = contexte.getSystemService(NotificationManager::class.java) ?: return
        gestionnaire.createNotificationChannel(
            NotificationChannel(
                CANAL,
                contexte.getString(R.string.drift_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val ouvrir = PendingIntent.getActivity(
            contexte,
            0,
            Intent(contexte, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(contexte, CANAL)
            // A silhouette icon: Android would render the app icon as a white square.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contexte.getString(R.string.drift_title))
            .setContentText(resume(derive, catalogue))
            .setStyle(NotificationCompat.BigTextStyle().bigText(resume(derive, catalogue)))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        gestionnaire.notify(ID_NOTIFICATION, notification)
    }

    /** One sentence per kind of drift, then what to do about it. */
    private fun resume(derive: DeriveDemarrage, catalogue: Catalogue): String = buildList {
        if (derive.rallumes.isNotEmpty()) {
            add(
                contexte.resources.getQuantityString(
                    R.plurals.drift_packages,
                    derive.rallumes.size,
                    derive.rallumes.size,
                ),
            )
        }
        derive.accueilPerdu?.let { add(contexte.getString(R.string.drift_home, catalogue.nomDuLauncher(it))) }
        add(contexte.getString(R.string.drift_fix))
    }.joinToString(" ")

    private companion object {
        const val CANAL = "derive"
        const val ID_NOTIFICATION = 1
    }
}

/** Stock launchers known to the catalogue, which may only be disabled with a third-party launcher installed. */
fun Catalogue.accueilsUsine(): Set<String> = entrees.filter { it.requiertLauncherTiers }.map { it.paquet }.toSet()

/** Display name of a launcher, or its package name. */
fun Catalogue.nomDuLauncher(paquet: String): String = nomLauncher(paquet) ?: paquet
