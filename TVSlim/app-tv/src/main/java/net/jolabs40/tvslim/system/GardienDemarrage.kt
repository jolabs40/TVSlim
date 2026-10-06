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
 * Ce que fait le gardien à chaque allumage du téléviseur — une seule fois par allumage, quelle que soit la
 * porte par laquelle il arrive : le signal de démarrage ([DemarrageReceiver]) là où le fabricant le
 * laisse passer ; sinon [PassageActivity], qu'ouvre StartLight à l'allumage.
 *
 * ⚠️ **Sur une TCL, seule la seconde passe** (2026-10-06) : son gestionnaire de démarrage (`TclAppBoot`)
 * refuse aux applications tierces le signal de démarrage, les services et même la liaison d'un écouteur
 * de notifications, et remet à zéro à chaque allumage toute autorisation `AUTO_START` posée par ADB,
 * par paquet comme par uid. L'ouverture d'une activité, elle, n'est pas filtrée.
 *
 * D'abord constater ([GardienDerive], la photo de l'allumage), ensuite réappliquer les réglages que le
 * téléviseur remet à leur valeur d'usine — `low_power_standby_enabled` au premier chef, qui le rend
 * injoignable en veille. La photo ne dépend pas de `WRITE_SECURE_SETTINGS` ; la réapplication, si.
 */
@Singleton
class GardienDemarrage @Inject constructor(
    @ApplicationContext private val contexte: Context,
    private val preferences: PreferencesRepository,
    private val reglages: ReglagesSysteme,
    private val catalogue: CatalogueRepository,
    private val derive: GardienDerive,
) {

    /** Pour une porte qui se referme aussitôt (une activité) : le travail continue sans elle. */
    fun lancer(porte: String) {
        portee.launch { auDemarrage(porte) }
    }

    suspend fun auDemarrage(porte: String) {
        if (!preferences.gardienActifMaintenant()) return
        // Le compteur d'allumages d'Android distingue cet allumage du précédent : une porte peut s'ouvrir
        // deux fois — le signal de démarrage et StartLight, ou StartLight relancé —, le travail se fait une fois.
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
