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

/** État d'un paquet du catalogue sur ce téléviseur précis. */
enum class EtatPaquet { ABSENT, ACTIF, DESACTIVE }

/** Photographie de l'appareil, affichée avant et après une intervention. */
data class InfosAppareil(
    val marque: String,
    val modele: String,
    val versionAndroid: String,
    val build: String,
    val memoireTotaleMo: Long,
    val memoireLibreMo: Long,
    val paquetsInstalles: Int,
    val paquetsDesactives: Int,
    val accueilActuel: String,
    val launchersTiers: List<LauncherInstalle>,
) {
    companion object {
        val VIDE = InfosAppareil(
            marque = "", modele = "", versionAndroid = "", build = "",
            memoireTotaleMo = 0, memoireLibreMo = 0,
            paquetsInstalles = 0, paquetsDesactives = 0,
            accueilActuel = "", launchersTiers = emptyList(),
        )
    }
}

data class LauncherInstalle(
    val paquet: String,
    val nom: String,
    val composant: String,
)

/**
 * Lecture seule de l'état de l'appareil. Tout ce qui est ici fonctionne sans privilège :
 * seule l'écriture passe par Shizuku.
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

    fun etats(paquets: List<String>): Map<String, EtatPaquet> = paquets.associateWith { etat(it) }

    /** Paquet de l'écran d'accueil actuellement retenu par le système. */
    fun accueilActuel(): String {
        val intention = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return gestionnaire.resolveActivity(intention, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName.orEmpty()
    }

    /**
     * Launchers utilisables en remplacement de l'accueil d'usine : tout ce qui répond à
     * `category.HOME` sans faire partie des paquets d'accueil du catalogue.
     */
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
