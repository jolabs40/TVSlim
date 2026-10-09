package net.jolabs40.tvslim.fichiers

/** What a delete removes. A link removes only itself, never its target. */
enum class NatureSuppression { FICHIER, DOSSIER, LIEN }

/** A delete awaiting confirmation; for a folder, its recursive contents. */
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

    /** The folder is or contains a whole storage root; TV Slim does not delete it. */
    data object Protege : ExamenSuppression

    data class Illisible(val refus: RefusLecture, val motif: String = "") : ExamenSuppression
}

enum class IssueSuppression { SUPPRIME, PROTEGE, ECHEC }

/** [detail]: the TV's answer on failure (what could not be deleted, and why). */
data class SuppressionEntree(val issue: IssueSuppression, val nom: String, val detail: String = "")
