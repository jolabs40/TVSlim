package net.jolabs40.tvslim.files

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** State of the Files tab, shared by both apps. */
data class ExplorerState(
    val path: String = START_FOLDER,
    /** Last listing of [path]; null until the folder has been opened. */
    val reading: FolderRead? = null,
    val loading: Boolean = false,
    val shortcuts: List<Shortcut> = Shortcut.withVolumes(emptyList()),
    /** Reading the picked items, then re-reading the folder; confirmation comes next. */
    val review: Boolean = false,
    val confirmation: UploadPlan? = null,
    /** An upload, or a copy to the PC ([UploadProgress.direction] tells which). */
    val progress: UploadProgress? = null,
    /** Result of the last upload or copy, still readable once the banner is gone. */
    val last: UploadResult? = null,
    /** A TV folder is listed in full before being copied or deleted, so the confirmation can say what it holds. */
    val inventory: Boolean = false,
    /** Folder copy awaiting confirmation; a single file copy starts as soon as it is picked. */
    val pendingDownload: DownloadPlan? = null,
    val deletion: DeletionPlan? = null,
    val deleting: Boolean = false,
) {
    val entries: List<RemoteEntry> get() = (reading as? FolderRead.Read)?.entries.orEmpty()
    val parent: String? get() = RemotePath.parent(path)
    val steps: List<PathStep> get() = RemotePath.steps(path)
    val uploadInProgress: Boolean get() = progress != null

    /**
     * One operation at a time: checking, confirming, uploading, copying and deleting never overlap (deleting
     * the folder an upload is writing to, for example).
     */
    val busy: Boolean
        get() = review || inventory || confirmation != null || pendingDownload != null || deletion != null ||
            deleting || uploadInProgress
}

/** Events each app turns into a localized message. */
sealed interface FilesSignal {
    data object Busy : FilesSignal

    data class Rejection(val rejection: UploadRejection, val names: List<String>) : FilesSignal

    /** The picked local items cannot be read (protected folder, file gone). */
    data class LocalReadFailed(val reason: String) : FilesSignal

    data class Creation(val creation: FolderCreation) : FilesSignal

    /** An upload, or a copy to the PC ([UploadResult.direction]). */
    data class Upload(val result: UploadResult) : FilesSignal

    /** The contents of folder [name] could not be read; nothing was copied or deleted. */
    data class UnreadableContent(val name: String, val rejection: ReadRejection, val reason: String) : FilesSignal

    data class Deletion(val deletion: EntryDeletion) : FilesSignal
}

/**
 * The Files tab without its UI: current folder, its listing, the transfer in progress, what is about to be
 * deleted. Shared by the companion and Windows, which only add how files are picked (and their destination on
 * disk) and the wording.
 *
 * A listing that comes back after the user moved elsewhere is ignored. An upload keeps running while the user
 * browses other folders; its commands and the listings take turns on the same connection.
 */
class FileExplorer(
    private val browser: FileBrowser,
    private val scope: CoroutineScope,
    private val report: (FilesSignal) -> Unit,
) {

    private val _state = MutableStateFlow(ExplorerState())
    val state: StateFlow<ExplorerState> = _state.asStateFlow()

    private val cancellation = AtomicBoolean(false)

    /** Bumped by each [forget]: results from an earlier generation belong to another TV. */
    @Volatile
    private var generation = 0

    /** On entering the tab: reads the current folder and the volumes if not read yet. */
    fun start() {
        val current = _state.value
        if (current.reading == null && !current.loading) {
            open(current.path)
            readShortcuts()
        }
    }

    fun open(path: String) {
        val target = RemotePath.normalize(path)
        val round = generation
        // Reloading the same folder keeps the old listing until the answer; another folder clears it at once.
        _state.update { it.copy(path = target, loading = true, reading = if (it.path == target) it.reading else null) }
        scope.launch {
            val loaded = browser.listFolder(target)
            _state.update { if (round == generation && it.path == target) it.copy(reading = loaded, loading = false) else it }
        }
    }

    fun goUp() {
        _state.value.parent?.let(::open)
    }

    /** Reloads the folder and the volumes, since a USB drive may have been plugged in. */
    fun refresh() {
        open(_state.value.path)
        readShortcuts()
    }

    /**
     * Checks what [prepare] gathers (picked files, a walked folder) for upload into the current folder.
     * Reading local files can be slow and can fail, so it runs here under the checking indicator.
     */
    fun examine(prepare: suspend () -> LocalBatch) {
        if (_state.value.busy) return report(FilesSignal.Busy)
        val destination = _state.value.path
        val round = generation
        _state.update { it.copy(review = true) }
        scope.launch {
            val batch = try {
                prepare()
            } catch (error: Exception) {
                _state.update { it.copy(review = false) }
                report(FilesSignal.LocalReadFailed(error.message ?: error.javaClass.simpleName))
                return@launch
            }
            val review = browser.examine(batch, destination)
            if (round != generation) return@launch
            when (review) {
                is UploadReview.Ready -> _state.update { it.copy(review = false, confirmation = review.plan) }
                is UploadReview.Rejected -> {
                    _state.update { it.copy(review = false) }
                    report(FilesSignal.Rejection(review.rejection, review.names))
                }
            }
        }
    }

    fun cancelConfirmation() = _state.update { it.copy(confirmation = null) }

    fun confirm() {
        val plan = _state.value.confirmation ?: return
        val round = generation
        cancellation.set(false)
        _state.update {
            it.copy(confirmation = null, progress = UploadProgress("", 0, plan.batch.files.size, 0L, plan.batch.size))
        }
        scope.launch {
            val result = browser.upload(plan, cancelled = cancellation::get) { progress ->
                if (round == generation) _state.update { it.copy(progress = progress) }
            }
            if (round != generation) return@launch
            _state.update { it.copy(progress = null, last = result) }
            report(FilesSignal.Upload(result))
            // Shows the new files if the user is still in that folder.
            if (_state.value.path == plan.destination) open(plan.destination)
        }
    }

    /** Stops the upload or copy at the next block; the partial file is kept on neither side. */
    fun cancelUpload() = cancellation.set(true)

    /**
     * Copies [entry] from the current folder to [target] as [name]. A file starts right away: the Save As
     * dialog served as confirmation and already asked about overwriting. A folder is listed first and waits
     * for [confirmDownload], since it may weigh gigabytes.
     */
    fun download(entry: RemoteEntry, target: LocalTarget, name: String) {
        if (_state.value.busy) return report(FilesSignal.Busy)
        val folder = _state.value.path
        val round = generation
        _state.update { it.copy(inventory = entry.folder) }
        scope.launch {
            val review = browser.prepareDownload(folder, entry, target, name)
            if (round != generation) return@launch
            _state.update { it.copy(inventory = false) }
            when (review) {
                is DownloadReview.Ready ->
                    if (review.plan.folder) _state.update { it.copy(pendingDownload = review.plan) } else copy(review.plan)

                is DownloadReview.Unreadable -> report(FilesSignal.UnreadableContent(entry.name, review.rejection, review.reason))
            }
        }
    }

    fun confirmDownload() {
        _state.value.pendingDownload?.let(::copy)
    }

    fun cancelDownload() = _state.update { it.copy(pendingDownload = null) }

    private fun copy(plan: DownloadPlan) {
        val round = generation
        cancellation.set(false)
        _state.update {
            it.copy(
                pendingDownload = null,
                progress = UploadProgress("", 0, plan.files.size, 0L, plan.size, TransferDirection.DOWNLOAD),
            )
        }
        scope.launch {
            val result = browser.download(plan, cancelled = cancellation::get) { progress ->
                if (round == generation) _state.update { it.copy(progress = progress) }
            }
            if (round != generation) return@launch
            _state.update { it.copy(progress = null, last = result) }
            report(FilesSignal.Upload(result))
        }
    }

    /** Prepares deleting [entry] from the current folder; nothing is deleted before [confirmDeletion]. */
    fun requestDeletion(entry: RemoteEntry) {
        if (_state.value.busy) return report(FilesSignal.Busy)
        val folder = _state.value.path
        val round = generation
        _state.update { it.copy(inventory = entry.folder && !entry.link) }
        scope.launch {
            val review = browser.prepareDeletion(folder, entry)
            if (round != generation) return@launch
            _state.update { it.copy(inventory = false) }
            when (review) {
                is DeletionReview.Ready -> _state.update { it.copy(deletion = review.plan) }
                DeletionReview.Protected ->
                    report(FilesSignal.Deletion(EntryDeletion(DeletionOutcome.PROTECTED, entry.name)))

                is DeletionReview.Unreadable -> report(FilesSignal.UnreadableContent(entry.name, review.rejection, review.reason))
            }
        }
    }

    fun confirmDeletion() {
        val plan = _state.value.deletion ?: return
        val round = generation
        _state.update { it.copy(deletion = null, deleting = true) }
        scope.launch {
            val outcome = browser.delete(plan)
            if (round != generation) return@launch
            _state.update { it.copy(deleting = false) }
            report(FilesSignal.Deletion(outcome))
            // Reloaded even after a failure: a partly deleted folder shows what is left.
            val parent = RemotePath.parent(plan.path)
            if (parent != null && _state.value.path == parent) open(parent)
        }
    }

    fun cancelDeletion() = _state.update { it.copy(deletion = null) }

    fun createFolder(name: String) {
        val parent = _state.value.path
        val round = generation
        scope.launch {
            val creation = browser.createFolder(parent, name)
            if (round != generation) return@launch
            report(FilesSignal.Creation(creation))
            if (creation.outcome == CreationOutcome.CREATED && _state.value.path == parent) open(parent)
        }
    }

    /** On disconnect or when switching TVs: nothing read so far applies to the next one. */
    fun forget() {
        generation++
        cancellation.set(true)
        _state.value = ExplorerState()
    }

    private fun readShortcuts() {
        val round = generation
        scope.launch {
            val shortcuts = browser.shortcuts()
            if (round == generation) _state.update { it.copy(shortcuts = shortcuts) }
        }
    }
}
