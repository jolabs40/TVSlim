package net.jolabs40.tvslim.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import net.jolabs40.tvslim.catalog.ReglageSysteme
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the TV's system settings.
 *
 * Writes go directly through `WRITE_SECURE_SETTINGS`, granted once over ADB
 * (`adb shell pm grant net.jolabs40.tvslim android.permission.WRITE_SECURE_SETTINGS`). It is the only such
 * permission that survives reboots, which is what lets the boot guard work without the phone.
 */
@Singleton
class ReglagesSysteme @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

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

    /** Writes a value. Returns an error message, or `null` on success. */
    fun ecrire(reglage: ReglageSysteme, valeur: String): String? {
        if (!ecritureDirectePossible()) {
            return "Autorisation d'écriture des réglages non accordée."
        }
        val erreur = runCatching {
            val resolveur = contexte.contentResolver
            when (reglage.portee) {
                PORTEE_SECURE -> Settings.Secure.putString(resolveur, reglage.cle, valeur)
                PORTEE_SYSTEM -> Settings.System.putString(resolveur, reglage.cle, valeur)
                else -> Settings.Global.putString(resolveur, reglage.cle, valeur)
            }
        }.exceptionOrNull()
        return erreur?.message ?: erreur?.javaClass?.simpleName
    }

    companion object {
        const val PORTEE_GLOBAL = "global"
        const val PORTEE_SECURE = "secure"
        const val PORTEE_SYSTEM = "system"
    }
}
