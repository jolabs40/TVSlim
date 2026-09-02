package net.jolabs40.tvslim.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/** Phase courante de l'installation intégrée, observée par l'interface. */
enum class PhaseInstallation {
    INACTIVE,
    RECHERCHE,
    TELECHARGEMENT,
    VERIFICATION,
    ATTENTE_CONFIRMATION,
    SUCCES,
    ECHEC,
}

data class EtatInstallation(
    val phase: PhaseInstallation = PhaseInstallation.INACTIVE,
    val pourcent: Int = 0,
    val detail: String = "",
) {
    val enCours: Boolean
        get() = phase != PhaseInstallation.INACTIVE &&
            phase != PhaseInstallation.SUCCES &&
            phase != PhaseInstallation.ECHEC
}

/**
 * Canal unique par lequel passe l'état de l'installation. Un objet plutôt qu'une injection :
 * le récepteur est déclaré dans le manifeste et ne partage rien d'autre avec l'application.
 */
object SuiviInstallation {
    val etat = MutableStateFlow(EtatInstallation())

    fun publier(phase: PhaseInstallation, pourcent: Int = 0, detail: String = "") {
        etat.value = EtatInstallation(phase, pourcent, detail)
    }
}

/**
 * Reçoit le verdict de `PackageInstaller`.
 *
 * Le premier retour est presque toujours `STATUS_PENDING_USER_ACTION` : le système exige que
 * la personne confirme l'installation elle-même. C'est voulu — l'application ne peut pas
 * installer quoi que ce soit dans le dos de qui que ce soit.
 */
class InstallationReceiver : BroadcastReceiver() {

    override fun onReceive(contexte: Context, intention: Intent) {
        val statut = intention.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        val message = intention.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (statut) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intention.extraIntent()
                if (confirmation == null) {
                    SuiviInstallation.publier(
                        PhaseInstallation.ECHEC,
                        detail = "Écran de confirmation introuvable.",
                    )
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                SuiviInstallation.publier(PhaseInstallation.ATTENTE_CONFIRMATION)
                runCatching { contexte.startActivity(confirmation) }
                    .onFailure {
                        SuiviInstallation.publier(
                            PhaseInstallation.ECHEC,
                            detail = it.message ?: "Confirmation impossible.",
                        )
                    }
            }

            PackageInstaller.STATUS_SUCCESS ->
                SuiviInstallation.publier(PhaseInstallation.SUCCES)

            else -> {
                Log.w(TAG, "Installation refusée : statut=$statut $message")
                SuiviInstallation.publier(
                    PhaseInstallation.ECHEC,
                    detail = message.ifBlank { "Installation refusée (code $statut)." },
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraIntent(): Intent? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val TAG = "TVSlim/Installation"
    }
}
