package net.jolabs40.tvslim.moteur

/**
 * Ce que le moteur dit d'une action — un refus, ou un échec dont le téléviseur n'a rien dit — sous forme de
 * motif typé, que chaque application rédige dans la langue de la personne depuis ses ressources.
 *
 * Le noyau n'écrit plus de phrase (constat H7 de l'audit) : il est compilé par le compagnon Android et par la
 * version Windows, dont les interfaces sont en anglais par défaut. Ce que le téléviseur répond reste, lui, dans
 * [ResultatAction.message], tel quel : on ne traduit pas ce qu'on n'a pas écrit.
 */
sealed interface MotifMoteur {

    /** Le paquet n'est pas sur cet appareil. */
    data object PaquetAbsent : MotifMoteur

    /** Rien à faire, il l'était déjà : accompagne un succès. */
    data object DejaDesactive : MotifMoteur

    /** Le téléviseur a refusé sans rien répondre. */
    data object EchecInexplique : MotifMoteur

    /** Un nom qui partirait dans le shell n'en est pas un : refusé avant tout envoi. */
    data class NomInvalide(val nature: NatureNom, val valeur: String) : MotifMoteur

    /** Un mode d'app-op qu'`appops` n'accepte pas. */
    data class ModeAppOpInconnu(val mode: String) : MotifMoteur

    /** Liste noire du catalogue. [raison] vient du catalogue, déjà dans la langue de l'appareil. */
    data class Protege(val raison: String) : MotifMoteur

    /** L'accueil d'usine ne se coupe pas sans launcher tiers : le téléviseur démarrerait sur un écran vide. */
    data object SansLauncherTiers : MotifMoteur

    /** L'application ne demande pas cette permission dans son manifeste : rien à accorder. */
    data class PermissionNonDemandee(val paquet: String, val permission: String) : MotifMoteur

    /** L'application n'a aucune activité à ouvrir depuis un menu. */
    data object AucuneActivite : MotifMoteur

    /** Ce n'est pas une application installée par la personne : elle ne se désinstalle pas. */
    data object PasInstalleeParLaPersonne : MotifMoteur
}

/** Ce qu'un [MotifMoteur.NomInvalide] devait nommer. */
enum class NatureNom { PAQUET, PERMISSION, APP_OP, COMPOSANT }

/** Le motif d'un échec dont le téléviseur n'a rien dit ; aucun quand il a répondu quelque chose. */
internal fun motifSiMuet(reussi: Boolean, sortie: String): MotifMoteur? =
    if (!reussi && sortie.isBlank()) MotifMoteur.EchecInexplique else null
