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
 * La dérive vue du téléviseur : à chaque allumage, une photo — firmware, paquets du catalogue
 * désactivés, accueil en place — comparée à celle du précédent. Une mise à jour système qui a rallumé
 * des paquets, ou rendu l'accueil à Google TV, laisse un rapport que l'écran d'accueil de l'application
 * montre, et une notification quand Android le permet.
 *
 * ⚠️ **Il constate, il ne répare pas.** Désactiver un paquet demande une session ADB, que seuls le
 * téléphone et le PC ouvrent ; ce sont eux qui proposent de tout remettre, d'après leur journal
 * (`planDeDerive`). Le gardien dit pourquoi, et quand.
 *
 * Lecture seule, par `PackageManager` : aucune permission privilégiée n'est nécessaire.
 */
@Singleton
class GardienDerive @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val catalogueRepo: CatalogueRepository,
    private val appareil: AppareilRepository,
    private val preferences: PreferencesRepository,
) {

    /** Photographie cet allumage, le compare au précédent, et retient ce qui a dérivé. */
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
        // Un rapport ancien reste tant que rien ne l'a repris : un allumage sans mise à jour ne l'efface pas.
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
            // Une silhouette : de l'icône de l'application, Android ne garderait qu'un carré blanc.
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contexte.getString(R.string.drift_title))
            .setContentText(resume(derive, catalogue))
            .setStyle(NotificationCompat.BigTextStyle().bigText(resume(derive, catalogue)))
            .setContentIntent(ouvrir)
            .setAutoCancel(true)
            .build()
        gestionnaire.notify(ID_NOTIFICATION, notification)
    }

    /** Une phrase par nature de dérive, puis ce qu'il faut faire. */
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

/** Les accueils d'usine que le catalogue connaît : ceux qu'on ne coupe qu'avec un launcher tiers en place. */
fun Catalogue.accueilsUsine(): Set<String> = entrees.filter { it.requiertLauncherTiers }.map { it.paquet }.toSet()

/** Le nom lisible d'un launcher, son paquet à défaut. */
fun Catalogue.nomDuLauncher(paquet: String): String = nomLauncher(paquet) ?: paquet
