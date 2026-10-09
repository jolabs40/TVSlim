package net.jolabs40.tvslim.moteur

/**
 * What the engine says about an action (a refusal, or a failure the TV gave no reason for), as a typed
 * reason that each app words in the user's language from its own resources.
 *
 * The core writes no sentences: it is compiled into the Android companion and the Windows app, whose UIs
 * default to English. The TV's own answer stays untranslated in [ResultatAction.message].
 */
sealed interface MotifMoteur {

    /** The package is not on this device. */
    data object PaquetAbsent : MotifMoteur

    /** Nothing to do, already disabled; comes with a success. */
    data object DejaDesactive : MotifMoteur

    /** The TV refused without any message. */
    data object EchecInexplique : MotifMoteur

    /** A name bound for the shell is not a valid identifier; rejected before sending. */
    data class NomInvalide(val nature: NatureNom, val valeur: String) : MotifMoteur

    /** An app-op mode that `appops` does not accept. */
    data class ModeAppOpInconnu(val mode: String) : MotifMoteur

    /** On the catalogue blocklist. [raison] comes from the catalogue, already in the device language. */
    data class Protege(val raison: String) : MotifMoteur

    /** The stock home screen cannot be disabled without a third-party launcher: the TV would boot to nothing. */
    data object SansLauncherTiers : MotifMoteur

    /** The app does not request this permission in its manifest, so there is nothing to grant. */
    data class PermissionNonDemandee(val paquet: String, val permission: String) : MotifMoteur

    /** The app has no launcher activity to open. */
    data object AucuneActivite : MotifMoteur

    /** Not an app installed by the user, so it cannot be uninstalled here. */
    data object PasInstalleeParLaPersonne : MotifMoteur
}

/** What a [MotifMoteur.NomInvalide] was supposed to name. */
enum class NatureNom { PAQUET, PERMISSION, APP_OP, COMPOSANT }

/** Reason for a failure the TV said nothing about; null when it did answer something. */
internal fun motifSiMuet(reussi: Boolean, sortie: String): MotifMoteur? =
    if (!reussi && sortie.isBlank()) MotifMoteur.EchecInexplique else null
