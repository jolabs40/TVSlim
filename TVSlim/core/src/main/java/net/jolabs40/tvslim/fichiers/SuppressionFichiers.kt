package net.jolabs40.tvslim.fichiers

/** Ce qu'efface une suppression : un lien n'emporte que lui-même, jamais ce vers quoi il mène. */
enum class NatureSuppression { FICHIER, DOSSIER, LIEN }

/** Une suppression soumise à confirmation : pour un dossier, ce qu'il contient, à toute profondeur. */
data class PlanSuppression(
    val chemin: String,
    val nature: NatureSuppression,
    val fichiers: Int = 0,
    val dossiers: Int = 0,
    val taille: Long = 0L,
) {
    val nom: String get() = CheminDistant.nom(chemin)
}

sealed interface ExamenSuppression {
    data class Pret(val plan: PlanSuppression) : ExamenSuppression

    /** Le dossier est un stockage entier, ou en contient un : TV Slim ne l'efface pas. */
    data object Protege : ExamenSuppression

    data class Illisible(val refus: RefusLecture, val motif: String = "") : ExamenSuppression
}

enum class IssueSuppression { SUPPRIME, PROTEGE, ECHEC }

/** [detail] : la réponse du téléviseur à un échec — ce qui n'a pas pu être effacé, et pourquoi. */
data class SuppressionEntree(val issue: IssueSuppression, val nom: String, val detail: String = "")
