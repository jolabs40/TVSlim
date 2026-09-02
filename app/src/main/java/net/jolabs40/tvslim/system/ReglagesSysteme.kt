package net.jolabs40.tvslim.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import net.jolabs40.tvslim.catalog.ReglageSysteme
import net.jolabs40.tvslim.privileged.ShizukuPasserelle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lecture et écriture des réglages système.
 *
 * Deux voies, dans cet ordre :
 *  1. écriture directe, si `WRITE_SECURE_SETTINGS` a été accordée une fois par ADB
 *     (`pm grant net.jolabs40.tvslim android.permission.WRITE_SECURE_SETTINGS`). Elle survit
 *     aux redémarrages, ce qui permet au gardien de démarrage de travailler sans Shizuku ;
 *  2. à défaut, `settings put` dans le process privilégié de Shizuku.
 */
@Singleton
class ReglagesSysteme @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val passerelle: ShizukuPasserelle,
) {

    /** Vrai si l'application peut écrire elle-même, sans Shizuku — donc aussi au démarrage. */
    fun ecritureDirectePossible(): Boolean =
        contexte.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun lire(reglage: ReglageSysteme): String? = runCatching {
        val resolveur = contexte.contentResolver
        when (reglage.portee) {
            PORTEE_SECURE -> Settings.Secure.getString(resolveur, reglage.cle)
            PORTEE_SYSTEM -> Settings.System.getString(resolveur, reglage.cle)
            else -> Settings.Global.getString(resolveur, reglage.cle)
        }
    }.getOrNull()

    /** Applique une valeur. Renvoie un message d'erreur, ou `null` en cas de succès. */
    suspend fun ecrire(reglage: ReglageSysteme, valeur: String): String? {
        if (ecritureDirectePossible()) {
            val erreur = runCatching {
                val resolveur = contexte.contentResolver
                when (reglage.portee) {
                    PORTEE_SECURE -> Settings.Secure.putString(resolveur, reglage.cle, valeur)
                    PORTEE_SYSTEM -> Settings.System.putString(resolveur, reglage.cle, valeur)
                    else -> Settings.Global.putString(resolveur, reglage.cle, valeur)
                }
            }.exceptionOrNull()
            if (erreur == null) return null
            Log.w(TAG, "Écriture directe refusée pour ${reglage.cle}", erreur)
        }
        val resultat = passerelle.executer("settings put ${reglage.portee} ${reglage.cle} $valeur")
        return if (resultat.reussi) null else resultat.sortie.ifBlank { "Échec de l'écriture." }
    }

    companion object {
        const val PORTEE_GLOBAL = "global"
        const val PORTEE_SECURE = "secure"
        const val PORTEE_SYSTEM = "system"
        private const val TAG = "TVSlim/Reglages"
    }
}
