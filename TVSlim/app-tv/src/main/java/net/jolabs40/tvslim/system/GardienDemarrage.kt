package net.jolabs40.tvslim.system

import android.content.Context
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.catalog.CatalogueRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Boot guard work, run once per boot whichever path triggers it: the boot broadcast ([DemarrageReceiver])
 * where the manufacturer lets it through, otherwise [PassageActivity], which StartLight opens at power-on.
 *
 * On TCL only the second path works. Its boot manager (`TclAppBoot`) denies third-party apps the boot
 * broadcast, services and even notification listener binding, and resets any `AUTO_START` grant made over
 * ADB (per package or per uid) on every boot. Starting an activity is not filtered.
 *
 * First takes the boot snapshot ([GardienDerive]), then reapplies settings the TV resets to factory values,
 * notably `low_power_standby_enabled`, which makes it unreachable in standby. Only the reapply step needs
 * `WRITE_SECURE_SETTINGS`.
 */
@Singleton
class GardienDemarrage @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val preferences: PreferencesRepository,
    private val reglages: ReglagesSysteme,
    private val catalogue: CatalogueRepository,
    private val derive: GardienDerive,
) {

    /** For a caller that finishes right away (an activity): the work outlives it. */
    fun lancer(porte: String) {
        portee.launch { auDemarrage(porte) }
    }

    suspend fun auDemarrage(porte: String) {
        if (!preferences.gardienActifMaintenant()) return
        // Android's boot count tells boots apart. Two triggers may fire for one boot (broadcast and
        // StartLight, or StartLight relaunched); the work runs once.
        val allumage = Settings.Global.getInt(contexte.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (allumage >= 0 && !preferences.prendreAllumage(allumage)) {
            Log.i(TAG, "Allumage $allumage déjà traité ($porte)")
            return
        }
        Log.i(TAG, "Gardien de l'allumage $allumage, par $porte")

        runCatching { derive.verifier() }
            .onSuccess { constat -> constat?.let { Log.i(TAG, "Dérive après mise à jour : $it") } }
            .onFailure { Log.w(TAG, "Dérive non vérifiée", it) }
        if (!reglages.ecritureDirectePossible()) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS absente : réglages non réappliqués.")
            return
        }
        catalogue.catalogue().reglages
            .filter { it.reappliquerAuDemarrage }
            .forEach { reglage ->
                val erreur = reglages.ecrire(reglage, reglage.valeurOptimisee)
                if (erreur == null) {
                    Log.i(TAG, "${reglage.cle} remis à ${reglage.valeurOptimisee}")
                } else {
                    Log.w(TAG, "${reglage.cle} : $erreur")
                }
            }
    }

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "TVSlim/Demarrage"
    }
}
