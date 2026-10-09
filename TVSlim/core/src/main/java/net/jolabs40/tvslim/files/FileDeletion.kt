package net.jolabs40.tvslim.files

/** What a delete removes. A link removes only itself, never its target. */
enum class DeletionKind { FILE, FOLDER, LINK }

/** A delete awaiting confirmation; for a folder, its recursive contents. */
data class DeletionPlan(
    val path: String,
    val kind: DeletionKind,
    val files: Int = 0,
    val folders: Int = 0,
    val size: Long = 0L,
) {
    val name: String get() = RemotePath.name(path)
}

sealed interface DeletionReview {
    data class Ready(val plan: DeletionPlan) : DeletionReview

    /** The folder is or contains a whole storage root; TV Slim does not delete it. */
    data object Protected : DeletionReview

    data class Unreadable(val rejection: ReadRejection, val reason: String = "") : DeletionReview
}

enum class DeletionOutcome { DELETED, PROTECTED, FAILED }

/** [detail]: the TV's answer on failure (what could not be deleted, and why). */
data class EntryDeletion(val outcome: DeletionOutcome, val name: String, val detail: String = "")
