package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jolabs40.tvslim.files.RemoteEntry
import net.jolabs40.tvslim.files.FileExplorer
import net.jolabs40.tvslim.files.CreationOutcome
import net.jolabs40.tvslim.files.DeletionOutcome
import net.jolabs40.tvslim.files.FileBrowser
import net.jolabs40.tvslim.files.UploadRejection
import net.jolabs40.tvslim.files.ReadRejection
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.files.TransferDirection
import net.jolabs40.tvslim.files.FilesSignal
import net.jolabs40.tvslim.support.SupportInvitation
import net.jolabs40.tvslim.windows.adb.AdbClient
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.files.DiskTarget
import net.jolabs40.tvslim.windows.files.batchFrom
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.files_refused_silent_line
import net.jolabs40.tvslim.windows.resources.files_busy
import net.jolabs40.tvslim.windows.resources.files_content_denied
import net.jolabs40.tvslim.windows.resources.files_content_failed
import net.jolabs40.tvslim.windows.resources.files_content_not_found
import net.jolabs40.tvslim.windows.resources.files_copied
import net.jolabs40.tvslim.windows.resources.files_copied_cancelled
import net.jolabs40.tvslim.windows.resources.files_copied_interrupted
import net.jolabs40.tvslim.windows.resources.files_delete_failed
import net.jolabs40.tvslim.windows.resources.files_delete_protected
import net.jolabs40.tvslim.windows.resources.files_deleted
import net.jolabs40.tvslim.windows.resources.files_folder_created
import net.jolabs40.tvslim.windows.resources.files_folder_exists
import net.jolabs40.tvslim.windows.resources.files_folder_failed
import net.jolabs40.tvslim.windows.resources.files_folder_invalid
import net.jolabs40.tvslim.windows.resources.files_local_failed
import net.jolabs40.tvslim.windows.resources.files_refused_destination
import net.jolabs40.tvslim.windows.resources.files_refused_empty
import net.jolabs40.tvslim.windows.resources.files_refused_kind
import net.jolabs40.tvslim.windows.resources.files_refused_name
import net.jolabs40.tvslim.windows.resources.files_sent
import net.jolabs40.tvslim.windows.resources.files_sent_cancelled
import net.jolabs40.tvslim.windows.resources.files_sent_interrupted
import java.io.File

/**
 * Files tab: the core's explorer, shared with the companion, plus the Windows side (local files dropped or
 * picked, the destination of copies from the TV) and the messages.
 */
class FilesController(
    client: AdbClient,
    scope: CoroutineScope,
    private val show: (UiMessage) -> Unit,
    /** An upload or copy completed: the support banner may show. */
    private val thank: () -> Unit,
) {

    val explorer = FileExplorer(FileBrowser(client, client, client), scope) { signal ->
        show(signal.message())
        if (SupportInvitation.deserves(signal)) thank()
    }

    init {
        // What was read belongs to the TV: forget it on disconnect or when switching TVs, but not when
        // reconnecting to the same one.
        scope.launch {
            client.connection
                .map { if (it.state == ConnectionState.DISCONNECTED) "" else it.host }
                .distinctUntilChanged()
                .drop(1)
                .collect { explorer.forget() }
        }
    }

    /** Uploads dropped or picked files to the current folder. */
    fun upload(elements: List<File>) {
        if (elements.isEmpty()) return
        explorer.examine { withContext(Dispatchers.IO) { batchFrom(elements) } }
    }

    /**
     * Copies [entry] to disk. For a file, [choice] is the file picked in the save dialog (its name may differ);
     * for a folder, it is the folder that receives it.
     */
    fun copy(entry: RemoteEntry, choice: File) {
        val absolute = choice.absoluteFile
        if (entry.folder) {
            explorer.download(entry, DiskTarget(absolute), entry.name)
        } else {
            explorer.download(entry, DiskTarget(absolute.parentFile ?: return), absolute.name)
        }
    }

    private fun FilesSignal.message(): UiMessage = when (this) {
        FilesSignal.Busy -> text(Res.string.files_busy)
        is FilesSignal.Rejection -> when (rejection) {
            UploadRejection.EMPTY -> text(Res.string.files_refused_empty)
            UploadRejection.INVALID_NAME -> text(Res.string.files_refused_name, names.joinToString(", "))
            UploadRejection.KIND_MISMATCH -> text(Res.string.files_refused_kind, names.joinToString(", "))
            UploadRejection.UNREADABLE_DESTINATION -> text(Res.string.files_refused_destination)
        }

        is FilesSignal.LocalReadFailed -> text(Res.string.files_local_failed, reason)
        is FilesSignal.Creation -> when (creation.outcome) {
            CreationOutcome.CREATED -> text(Res.string.files_folder_created, creation.name)
            CreationOutcome.INVALID_NAME -> text(Res.string.files_folder_invalid)
            CreationOutcome.EXISTS -> text(Res.string.files_folder_exists, creation.name)
            CreationOutcome.FAILED -> text(Res.string.files_folder_failed, UiMessage.Raw(creation.detail))
        }

        is FilesSignal.Upload -> UiMessage.Lines(
            listOf(summary(result)) +
                result.failures.take(MAX_FAILURES).map {
                    if (it.reason.isBlank()) text(Res.string.files_refused_silent_line, it.path) else UiMessage.Raw("${it.path} : ${it.reason}")
                },
        )

        is FilesSignal.UnreadableContent -> when (rejection) {
            ReadRejection.NOT_FOUND -> text(Res.string.files_content_not_found, name)
            ReadRejection.DENIED -> text(Res.string.files_content_denied, name)
            ReadRejection.FAILED -> text(Res.string.files_content_failed, name, UiMessage.Raw(reason))
        }

        is FilesSignal.Deletion -> when (deletion.outcome) {
            DeletionOutcome.DELETED -> text(Res.string.files_deleted, deletion.name)
            DeletionOutcome.PROTECTED -> text(Res.string.files_delete_protected, deletion.name)
            DeletionOutcome.FAILED ->
                text(Res.string.files_delete_failed, deletion.name, UiMessage.Raw(deletion.detail.lines().first()))
        }
    }

    private fun summary(result: UploadResult): UiMessage = when (result.direction) {
        TransferDirection.UPLOAD -> when {
            result.cancelled -> text(Res.string.files_sent_cancelled, result.sentCount, result.count)
            result.interrupted -> text(Res.string.files_sent_interrupted, result.sentCount, result.count)
            else -> text(Res.string.files_sent, result.sentCount, result.count, result.destination)
        }

        TransferDirection.DOWNLOAD -> when {
            result.cancelled -> text(Res.string.files_copied_cancelled, result.sentCount, result.count)
            result.interrupted -> text(Res.string.files_copied_interrupted, result.sentCount, result.count)
            else -> text(Res.string.files_copied, result.sentCount, result.count, result.destination)
        }
    }

    private companion object {
        /** More would overflow the snackbar; the tab's card keeps the full list. */
        const val MAX_FAILURES = 4
    }
}
