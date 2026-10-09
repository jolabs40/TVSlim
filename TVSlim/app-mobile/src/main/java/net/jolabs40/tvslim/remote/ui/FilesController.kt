package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.files.FileExplorer
import net.jolabs40.tvslim.files.CreationOutcome
import net.jolabs40.tvslim.files.DeletionOutcome
import net.jolabs40.tvslim.files.FileBrowser
import net.jolabs40.tvslim.files.UploadRejection
import net.jolabs40.tvslim.files.ReadRejection
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.files.FilesSignal
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.AdbClient
import net.jolabs40.tvslim.remote.adb.ConnectionState
import net.jolabs40.tvslim.remote.files.documentBatch
import net.jolabs40.tvslim.remote.files.folderBatch
import net.jolabs40.tvslim.remote.files.documentName
import net.jolabs40.tvslim.remote.files.folderName
import net.jolabs40.tvslim.support.SupportInvitation

/**
 * Items picked on the phone, waiting for a destination folder on the TV: individual documents or a whole
 * folder ([tree]). [name] is the name shown (the first document's, or the folder's).
 */
data class PendingUpload(
    val documents: List<Uri> = emptyList(),
    val tree: Uri? = null,
    val name: String,
) {
    val folder: Boolean get() = tree != null
    val count: Int get() = documents.size
}

/**
 * Files tab: the core's file explorer (shared with Windows) plus the phone-specific parts, picking documents
 * with the Android picker and turning results into messages.
 *
 * On the phone the user picks what to send first, then where: the selection waits in [pending] until the
 * destination folder is opened and the upload confirmed. Browsing first did not make it clear that a
 * destination was being chosen.
 *
 * Shares only the coroutine scope and the banner with [RemoteViewModel].
 */
class FilesController(
    private val context: Context,
    client: AdbClient,
    private val scope: CoroutineScope,
    private val show: (String) -> Unit,
    /** Called after a completed upload; may show the support banner. */
    private val thank: () -> Unit,
) {

    val explorer = FileExplorer(FileBrowser(client, client), scope) { signal ->
        show(signal.message())
        if (SupportInvitation.deserves(signal)) thank()
    }

    private val _pending = MutableStateFlow<PendingUpload?>(null)
    val pending: StateFlow<PendingUpload?> = _pending.asStateFlow()

    init {
        // Forget what was read, and any pending upload, on disconnect or when switching TVs. A reconnect to the
        // same TV keeps it.
        scope.launch {
            client.connection
                .map { if (it.state == ConnectionState.DISCONNECTED) "" else it.host }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    _pending.value = null
                    explorer.forget()
                }
        }
    }

    fun chooseDocuments(documents: List<Uri>) {
        if (documents.isEmpty()) return
        scope.launch {
            val name = withContext(Dispatchers.IO) { documentName(context, documents.first()) }
            _pending.value = PendingUpload(documents = documents.distinct(), name = name)
        }
    }

    /** Picks a whole folder, subfolders included. */
    fun chooseFolder(tree: Uri) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { folderName(context, tree) }
            _pending.value = PendingUpload(tree = tree, name = name)
        }
    }

    /**
     * Uses the displayed folder as destination: the pending items are examined, then confirmed. A declined
     * confirmation or an unsuitable folder returns to choosing the destination, keeping the selection.
     */
    fun uploadHere() {
        val waiting = _pending.value ?: return
        val tree = waiting.tree
        explorer.examine {
            withContext(Dispatchers.IO) {
                if (tree != null) folderBatch(context, tree) else documentBatch(context, waiting.documents)
            }
        }
    }

    fun abandonUpload() {
        _pending.value = null
    }

    fun confirm() {
        _pending.value = null
        explorer.confirm()
    }

    private fun FilesSignal.message(): String = when (this) {
        FilesSignal.Busy -> context.getString(R.string.files_busy)
        is FilesSignal.Rejection -> when (rejection) {
            UploadRejection.EMPTY -> context.getString(R.string.files_refused_empty)
            UploadRejection.INVALID_NAME -> context.getString(R.string.files_refused_name, names.joinToString(", "))
            UploadRejection.KIND_MISMATCH -> context.getString(R.string.files_refused_kind, names.joinToString(", "))
            UploadRejection.UNREADABLE_DESTINATION -> context.getString(R.string.files_refused_destination)
        }

        is FilesSignal.LocalReadFailed -> context.getString(R.string.files_local_failed, reason)
        is FilesSignal.Creation -> when (creation.outcome) {
            CreationOutcome.CREATED -> context.getString(R.string.files_folder_created, creation.name)
            CreationOutcome.INVALID_NAME -> context.getString(R.string.files_folder_invalid)
            CreationOutcome.EXISTS -> context.getString(R.string.files_folder_exists, creation.name)
            CreationOutcome.FAILED -> context.getString(R.string.files_folder_failed, creation.detail)
        }

        // Per-file failures stay in the tab's card: a phone banner only has two lines.
        is FilesSignal.Upload -> summary(result)

        // Copy and delete are only offered on Windows so far, but the shared core can report them.
        is FilesSignal.UnreadableContent -> when (rejection) {
            ReadRejection.NOT_FOUND -> context.getString(R.string.files_content_not_found, name)
            ReadRejection.DENIED -> context.getString(R.string.files_content_denied, name)
            ReadRejection.FAILED -> context.getString(R.string.files_content_failed, name, reason)
        }

        is FilesSignal.Deletion -> when (deletion.outcome) {
            DeletionOutcome.DELETED -> context.getString(R.string.files_deleted, deletion.name)
            DeletionOutcome.PROTECTED -> context.getString(R.string.files_delete_protected, deletion.name)
            DeletionOutcome.FAILED ->
                context.getString(R.string.files_delete_failed, deletion.name, deletion.detail.lines().first())
        }
    }

    private fun summary(result: UploadResult): String = when {
        result.cancelled -> context.getString(R.string.files_sent_cancelled, result.sentCount, result.count)
        result.interrupted -> context.getString(R.string.files_sent_interrupted, result.sentCount, result.count)
        else -> context.getString(R.string.files_sent, result.sentCount, result.count, result.destination)
    }
}
