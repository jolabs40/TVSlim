package net.jolabs40.tvslim.remote.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jolabs40.tvslim.installation.CauseEchec
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.remote.R
import java.util.Locale

/** Où en est l'installation d'un APK. */
sealed interface PhaseInstallation {
    /** Copie du fichier, lecture de son manifeste et du téléviseur, avant la confirmation. */
    data object Examen : PhaseInstallation

    data class Envoi(val envoye: Long, val total: Long) : PhaseInstallation

    /** Tout est parti : Android vérifie l'application et l'installe. */
    data object Installation : PhaseInstallation
}

/** Ce que la carte « Installer une application » affiche. */
@Immutable
data class EtatInstallation(
    val phase: PhaseInstallation? = null,
    /** Le bilan de la dernière installation, qui reste lisible une fois la bannière passée. */
    val derniere: ResultatInstallation? = null,
) {
    val occupee: Boolean get() = phase != null
}

/** Un refus d'Android, dit dans la langue de la personne. */
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

/** Des octets en mégaoctets, à une décimale et à la façon de la langue : « 48,3 » en français. */
fun megaoctets(octets: Long): String = String.format(Locale.getDefault(), "%.1f", octets / 1_000_000.0)
