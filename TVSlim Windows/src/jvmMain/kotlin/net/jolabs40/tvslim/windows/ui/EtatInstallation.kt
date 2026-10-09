package net.jolabs40.tvslim.windows.ui

import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.installation.CauseEchec
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.apk_cause_abi
import net.jolabs40.tvslim.windows.ressources.apk_cause_connection
import net.jolabs40.tvslim.windows.ressources.apk_cause_downgrade
import net.jolabs40.tvslim.windows.ressources.apk_cause_invalid
import net.jolabs40.tvslim.windows.ressources.apk_cause_other
import net.jolabs40.tvslim.windows.ressources.apk_cause_refused
import net.jolabs40.tvslim.windows.ressources.apk_cause_sdk
import net.jolabs40.tvslim.windows.ressources.apk_cause_signature
import net.jolabs40.tvslim.windows.ressources.apk_cause_split
import net.jolabs40.tvslim.windows.ressources.apk_cause_storage
import net.jolabs40.tvslim.windows.ressources.apk_cause_unsigned
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

sealed interface PhaseInstallation {
    /** Reading the file and the TV, before confirmation. */
    data object Examen : PhaseInstallation

    data class Envoi(val envoye: Long, val total: Long) : PhaseInstallation

    /** Upload done; Android is verifying and installing the app. */
    data object Installation : PhaseInstallation
}

/** State of the "Install an app" card, mirroring the companion's. */
@Immutable
data class EtatInstallation(
    val phase: PhaseInstallation? = null,
    /** Outcome of the last install, still readable after the snackbar is gone. */
    val derniere: ResultatInstallation? = null,
) {
    val occupee: Boolean get() = phase != null
}

fun CauseEchec.ressource(): StringResource = when (this) {
    CauseEchec.SIGNATURE_DIFFERENTE -> Res.string.apk_cause_signature
    CauseEchec.RETROGRADATION -> Res.string.apk_cause_downgrade
    CauseEchec.ANDROID_TROP_ANCIEN -> Res.string.apk_cause_sdk
    CauseEchec.ARCHITECTURE -> Res.string.apk_cause_abi
    CauseEchec.ESPACE -> Res.string.apk_cause_storage
    CauseEchec.NON_SIGNE -> Res.string.apk_cause_unsigned
    CauseEchec.INCOMPLET -> Res.string.apk_cause_split
    CauseEchec.REFUSEE -> Res.string.apk_cause_refused
    CauseEchec.INVALIDE -> Res.string.apk_cause_invalid
    CauseEchec.CONNEXION -> Res.string.apk_cause_connection
    CauseEchec.AUTRE -> Res.string.apk_cause_other
}

/** Bytes as megabytes, one decimal, in the default locale ("48,3" in French). */
fun megaoctets(octets: Long): String = String.format(Locale.getDefault(), "%.1f", octets / 1_000_000.0)
