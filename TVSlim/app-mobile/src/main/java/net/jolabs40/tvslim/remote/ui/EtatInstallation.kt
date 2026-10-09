package net.jolabs40.tvslim.remote.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.installation.CauseEchec
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.remote.R
import java.util.Locale

sealed interface PhaseInstallation {
    /** Copying the file, reading its manifest and the TV, before confirmation. */
    data object Examen : PhaseInstallation

    data class Envoi(val envoye: Long, val total: Long) : PhaseInstallation

    /** Upload done; Android is verifying and installing the package. */
    data object Installation : PhaseInstallation
}

/** State of the "Install an app" card. */
@Immutable
data class EtatInstallation(
    val phase: PhaseInstallation? = null,
    /** Outcome of the last install, still readable after the banner is gone. */
    val derniere: ResultatInstallation? = null,
) {
    val occupee: Boolean get() = phase != null
}

@StringRes
fun CauseEchec.ressource(): Int = when (this) {
    CauseEchec.SIGNATURE_DIFFERENTE -> R.string.apk_cause_signature
    CauseEchec.RETROGRADATION -> R.string.apk_cause_downgrade
    CauseEchec.ANDROID_TROP_ANCIEN -> R.string.apk_cause_sdk
    CauseEchec.ARCHITECTURE -> R.string.apk_cause_abi
    CauseEchec.ESPACE -> R.string.apk_cause_storage
    CauseEchec.NON_SIGNE -> R.string.apk_cause_unsigned
    CauseEchec.INCOMPLET -> R.string.apk_cause_split
    CauseEchec.REFUSEE -> R.string.apk_cause_refused
    CauseEchec.INVALIDE -> R.string.apk_cause_invalid
    CauseEchec.CONNEXION -> R.string.apk_cause_connection
    CauseEchec.AUTRE -> R.string.apk_cause_other
}

/** Formats bytes as megabytes with one decimal, using the locale's separator ("48,3" in French). */
fun megaoctets(octets: Long): String = String.format(Locale.getDefault(), "%.1f", octets / 1_000_000.0)
