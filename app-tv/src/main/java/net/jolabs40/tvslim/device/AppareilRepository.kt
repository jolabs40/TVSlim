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
 * Lecture locale de l'état du téléviseur, par `PackageManager` — la contrepartie du
 * `LecteurDistant` du noyau, qui fait le même travail par commandes shell depuis le compagnon.
 *
 * Tout est en lecture seule : cette application n'a plus aucun privilège d'écriture sur les
 * paquets. Le débloat se pilote depuis le compagnon mobile.
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

    /** Paquet de l'écran d'accueil actuellement retenu par le système. */
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
