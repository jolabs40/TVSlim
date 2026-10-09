package net.jolabs40.tvslim.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the TV's state locally through `PackageManager`. The core's `LecteurDistant` does the same
 * over shell commands from the companion.
 *
 * Read-only: this app has no write privilege on packages. Debloating is driven from the phone.
 */
@Singleton
class AppareilRepository @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    private val gestionnaire: PackageManager get() = contexte.packageManager

    suspend fun infos(paquetsDAccueil: Set<String>): InfosAppareil = withContext(Dispatchers.IO) {
        val memoire = ActivityManager.MemoryInfo().also { info ->
            (contexte.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(info)
        }
        val tous = gestionnaire.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        InfosAppareil(
            marque = Build.MANUFACTURER,
            modele = Build.MODEL,
            versionAndroid = Build.VERSION.RELEASE,
            build = Build.DISPLAY,
            memoireTotaleMo = memoire.totalMem / MO,
            memoireLibreMo = memoire.availMem / MO,
            paquetsInstalles = tous.count { it.enabled },
            paquetsDesactives = tous.count { !it.enabled },
            accueilActuel = accueilActuel(),
            launchersTiers = launchersTiers(paquetsDAccueil),
        )
    }

    fun etat(paquet: String): EtatPaquet = try {
        val info = gestionnaire.getApplicationInfo(paquet, PackageManager.MATCH_DISABLED_COMPONENTS)
        if (info.enabled) EtatPaquet.ACTIF else EtatPaquet.DESACTIVE
    } catch (_: PackageManager.NameNotFoundException) {
        EtatPaquet.ABSENT
    }

    /** Package of the home screen the system currently resolves. */
    fun accueilActuel(): String {
        val intention = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return gestionnaire.resolveActivity(intention, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName.orEmpty()
    }

    fun launchersTiers(paquetsDAccueil: Set<String>): List<LauncherInstalle> {
        val intention = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return gestionnaire.queryIntentActivities(intention, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .filter { it.activityInfo != null }
            .filter { it.activityInfo.packageName !in paquetsDAccueil }
            .filter { it.activityInfo.packageName != contexte.packageName }
            .filter { it.activityInfo.enabled }
            // FallbackHome also answers category.HOME but only shows a blank screen at boot. Counting
            // it as a replacement would allow disabling the stock launcher.
            .filter { it.priority >= 0 && !it.activityInfo.name.contains("FallbackHome", true) }
            .map { resolution ->
                LauncherInstalle(
                    paquet = resolution.activityInfo.packageName,
                    nom = resolution.loadLabel(gestionnaire).toString(),
                    composant = "${resolution.activityInfo.packageName}/${resolution.activityInfo.name}",
                )
            }
            .distinctBy { it.paquet }
            .toList()
    }

    private companion object {
        const val MO = 1024L * 1024L
    }
}
