package net.jolabs40.tvslim.files

import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.FileReceiver
import net.jolabs40.tvslim.shell.ShellResult

enum class CreationOutcome { CREATED, INVALID_NAME, EXISTS, FAILED }

data class FolderCreation(val outcome: CreationOutcome, val name: String, val detail: String = "")

/**
 * Browses TV folders, uploads files, copies them to the PC and deletes them, like `adb shell ls`, `adb push`,
 * `adb pull` and `adb shell rm`, without a PC for the companion or `adb.exe` for Windows.
 *
 * Runs with the ADB shell's permissions: shared storage (`/sdcard`, `Android/data` included), removable volumes
 * and `/data/local/tmp`. Elsewhere the TV refuses file by file; no list is kept here, it would go stale. The one
 * exception: a whole storage root is never deleted (`FolderInventory`).
 *
 * Nothing here is journaled (no setting changes, and deletes cannot be undone). The companion passes no
 * [fileReceiver]: it copies nothing to the phone.
 */
class FileBrowser(
    private val executor: CommandExecutor,
    private val uploader: FileUploader,
    private val fileReceiver: FileReceiver? = null,
) {

    /** A read, safe to replay after a disconnect, so it goes through the regular command path. */
    suspend fun listFolder(path: String): FolderRead {
        val normal = RemotePath.normalize(path)
        return FolderReader.read(normal, executor.execute(FolderReader.command(normal)))
    }

    suspend fun shortcuts(): List<Shortcut> {
        val readResult = executor.execute(FolderReader.VOLUMES_COMMAND)
        return Shortcut.withVolumes(if (readResult.succeeded) FolderReader.volumes(readResult.output) else emptyList())
    }

    /**
     * Checks what uploading [batch] into [destination] would do, without sending anything: names first, then
     * the folder, re-read now since it may have changed since it was displayed.
     */
    suspend fun examine(batch: LocalBatch, destination: String): UploadReview {
        if (batch.empty) return UploadReview.Rejected(UploadRejection.EMPTY)
        val invalidPaths = (batch.files.map { it.path } + batch.folders)
            .filter { path -> path.split('/').any { !RemotePath.isValidName(it) } }
        if (invalidPaths.isNotEmpty()) return UploadReview.Rejected(UploadRejection.INVALID_NAME, invalidPaths.take(MAX_NAMES))

        val reading = listFolder(destination) as? FolderRead.Read
            ?: return UploadReview.Rejected(UploadRejection.UNREADABLE_DESTINATION)
        val present = reading.entries.associateBy { it.name }
        val roots = batch.roots
        val conflicting = roots.filter { (name, folder) -> present[name]?.let { it.folder != folder } == true }
        if (conflicting.isNotEmpty()) {
            return UploadReview.Rejected(UploadRejection.KIND_MISMATCH, conflicting.keys.sorted().take(MAX_NAMES))
        }
        return UploadReview.Ready(UploadPlan(reading.path, batch, roots.keys.filter { it in present }.sorted()))
    }

    /**
     * Uploads a confirmed plan: folders first (empty ones included), then files one by one.
     *
     * A rejected file does not stop the others: Android's refusal is recorded and the upload goes on. A lost
     * connection does stop it. [cancelled] is checked between files and during each one.
     */
    suspend fun upload(
        plan: UploadPlan,
        cancelled: () -> Boolean = { false },
        onProgress: (UploadProgress) -> Unit = {},
    ): UploadResult {
        val files = plan.batch.files
        val total = plan.batch.size
        val summary = UploadResult(plan.destination, sentCount = 0, count = files.size)

        // `mkdir -p` ignores existing folders, so it is safe to replay after a disconnect.
        val folders = plan.batch.foldersToCreate.map { RemotePath.join(plan.destination, it) }
        for (batch in inBatches(folders)) {
            val creation = executor.execute("mkdir -p $batch")
            if (!creation.succeeded) {
                val reason = creation.output.ifBlank { "Code de retour ${creation.code}." }
                return summary.copy(failures = listOf(UploadFailure(plan.destination, reason)), interrupted = creation.code < 0)
            }
        }

        var sentCount = 0
        var sentSoFar = 0L
        val failures = mutableListOf<UploadFailure>()
        for ((index, file) in files.withIndex()) {
            if (cancelled()) return summary.copy(sentCount = sentCount, failures = failures, cancelled = true)
            val progress = UploadProgress(file.path, index + 1, files.size, sentSoFar, total)
            onProgress(progress)

            val source = try {
                file.open()
            } catch (error: Exception) {
                // A local file that cannot be read (locked, deleted since it was picked) does not stop the others.
                failures += UploadFailure(file.path, error.message ?: error.javaClass.simpleName)
                sentSoFar += file.size
                continue
            }
            val response = uploader.send(
                source = source,
                size = file.size,
                path = RemotePath.join(plan.destination, file.path),
                date = file.date,
                cancelled = cancelled,
            ) { sent -> onProgress(progress.copy(sent = sentSoFar + sent)) }
            sentSoFar += file.size

            when {
                response.succeeded -> sentCount++
                cancelled() -> return summary.copy(sentCount = sentCount, failures = failures, cancelled = true)
                response.code < 0 -> return summary.copy(
                    sentCount = sentCount,
                    failures = failures + UploadFailure(file.path, response.output),
                    interrupted = true,
                )

                // Empty when the TV refuses without a message; each app then shows its own localized text.
                else -> failures += UploadFailure(file.path, response.output)
            }
        }
        return summary.copy(sentCount = sentCount, failures = failures)
    }

    /** Creates an empty folder in [parent]. An existing name is reported rather than treated as success. */
    suspend fun createFolder(parent: String, name: String): FolderCreation {
        val cleanName = name.trim()
        if (!RemotePath.isValidName(cleanName)) return FolderCreation(CreationOutcome.INVALID_NAME, cleanName)
        val path = quote(RemotePath.join(RemotePath.normalize(parent), cleanName))
        val response = executor.execute("[ -e $path ] && exit $CODE_EXISTS; mkdir $path")
        return when {
            response.succeeded -> FolderCreation(CreationOutcome.CREATED, cleanName)
            response.code == CODE_EXISTS -> FolderCreation(CreationOutcome.EXISTS, cleanName)
            else -> FolderCreation(CreationOutcome.FAILED, cleanName, response.output.ifBlank { "Code de retour ${response.code}." })
        }
    }

    /**
     * Prepares copying [entry], seen in [folder], to [target] as [name]. A file needs nothing more; a folder
     * is listed recursively to know what will arrive and how big it is.
     */
    suspend fun prepareDownload(
        folder: String,
        entry: RemoteEntry,
        target: LocalTarget,
        name: String,
    ): DownloadReview {
        val source = RemotePath.join(RemotePath.normalize(folder), entry.name)
        if (!entry.folder) {
            val file = RemoteFile(source, name, entry.size, entry.date)
            return DownloadReview.Ready(DownloadPlan(source, target, name, folder = false, files = listOf(file)))
        }
        val loaded = FolderInventory.read(executor.execute(FolderInventory.command(source, guard = false)))
        return when (loaded) {
            is InventoryRead.Read -> DownloadReview.Ready(
                DownloadPlan(
                    source = source,
                    target = target,
                    name = name,
                    folder = true,
                    files = loaded.inventory.files.map {
                        RemoteFile(RemotePath.join(source, it.path), "$name/${it.path}", it.size, it.date)
                    },
                    folders = listOf(name) + loaded.inventory.folders.map { "$name/$it" },
                    alreadyExists = target.exists(name),
                ),
            )

            is InventoryRead.Unreadable -> DownloadReview.Unreadable(loaded.rejection, loaded.reason)
            // Only the listing before a delete runs the guard, so this cannot happen here.
            InventoryRead.Protected -> DownloadReview.Unreadable(ReadRejection.FAILED)
        }
    }

    /**
     * Copies a plan to the PC: folders first (empty ones included), then files one by one.
     *
     * As with uploads, a file rejected by the TV or by the disk does not stop the others; a lost connection
     * does. A file only reaches the target complete: if stopped or cut off, it leaves nothing behind.
     */
    suspend fun download(
        plan: DownloadPlan,
        cancelled: () -> Boolean = { false },
        onProgress: (UploadProgress) -> Unit = {},
    ): UploadResult {
        val fileReceiver = checkNotNull(fileReceiver) { "Cette application ne copie rien vers l'appareil." }
        val files = plan.files
        val summary = UploadResult(plan.destination, sentCount = 0, count = files.size, direction = TransferDirection.DOWNLOAD)

        for (folder in plan.folders) {
            try {
                plan.target.createFolder(folder)
            } catch (error: Exception) {
                // A folder the disk refuses would receive none of its contents.
                return summary.copy(failures = listOf(UploadFailure(folder, reason(error))))
            }
        }

        var copies = 0
        var receivedBytes = 0L
        val failures = mutableListOf<UploadFailure>()
        for ((index, file) in files.withIndex()) {
            if (cancelled()) return summary.copy(sentCount = copies, failures = failures, cancelled = true)
            val progress = UploadProgress(file.local, index + 1, files.size, receivedBytes, plan.size, TransferDirection.DOWNLOAD)
            onProgress(progress)

            val writing = try {
                plan.target.write(file.local)
            } catch (error: Exception) {
                // A file the disk refuses (protected folder, impossible name) does not stop the others.
                failures += UploadFailure(file.local, reason(error))
                receivedBytes += file.size
                continue
            }
            val response = writing.use { opened ->
                val incoming = fileReceiver.receive(file.remote, opened.stream, file.size, cancelled) { received ->
                    onProgress(progress.copy(sent = receivedBytes + received))
                }
                if (incoming.succeeded) commit(opened, file.date) else incoming
            }
            receivedBytes += file.size

            when {
                response.succeeded -> copies++
                cancelled() -> return summary.copy(sentCount = copies, failures = failures, cancelled = true)
                response.code < 0 -> return summary.copy(
                    sentCount = copies,
                    failures = failures + UploadFailure(file.local, response.output),
                    interrupted = true,
                )

                else -> failures += UploadFailure(file.local, response.output)
            }
        }
        return summary.copy(sentCount = copies, failures = failures)
    }

    /** Moves the received file into place; a disk refusal (file open elsewhere) counts as a failure. */
    private fun commit(writing: LocalWrite, date: Long): ShellResult = try {
        writing.commit(date)
        ShellResult(0, "")
    } catch (error: Exception) {
        ShellResult(1, reason(error))
    }

    /**
     * Prepares deleting [entry], seen in [folder]. A folder is listed recursively so the confirmation can say
     * what it holds, and a whole storage root is rejected right here.
     */
    suspend fun prepareDeletion(folder: String, entry: RemoteEntry): DeletionReview {
        val path = RemotePath.join(RemotePath.normalize(folder), entry.name)
        if (entry.link) return DeletionReview.Ready(DeletionPlan(path, DeletionKind.LINK))
        if (!entry.folder) {
            return DeletionReview.Ready(DeletionPlan(path, DeletionKind.FILE, files = 1, size = entry.size))
        }
        return when (val loaded = FolderInventory.read(executor.execute(FolderInventory.command(path, guard = true)))) {
            is InventoryRead.Read -> DeletionReview.Ready(
                DeletionPlan(
                    path = path,
                    kind = DeletionKind.FOLDER,
                    files = loaded.inventory.files.size,
                    folders = loaded.inventory.folders.size,
                    size = loaded.inventory.size,
                ),
            )

            InventoryRead.Protected -> DeletionReview.Protected
            is InventoryRead.Unreadable -> DeletionReview.Unreadable(loaded.rejection, loaded.reason)
        }
    }

    /** Deletes a confirmed plan. Whatever the TV refuses stays in place, and its answer says what. */
    suspend fun delete(plan: DeletionPlan): EntryDeletion {
        val response = executor.execute(FolderInventory.deletionCommand(plan.path, plan.kind))
        return FolderInventory.deletion(plan.name, response)
    }

    private fun reason(error: Exception): String = error.message ?: error.javaClass.simpleName

    /**
     * Groups quoted paths into commands of reasonable length: the command travels in the name of the ADB
     * service being opened, which old `adbd` versions limit to 4 KB.
     */
    private fun inBatches(paths: List<String>): List<String> {
        val batches = mutableListOf<String>()
        val current = StringBuilder()
        paths.map(::quote).forEach { quoted ->
            if (current.isNotEmpty() && current.length + 1 + quoted.length > MAX_LENGTH) {
                batches += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(quoted)
        }
        if (current.isNotEmpty()) batches += current.toString()
        return batches
    }

    private companion object {
        /** Beyond this, listing the offending names in a message stops being useful. */
        const val MAX_NAMES = 5

        const val MAX_LENGTH = 3_500

        const val CODE_EXISTS = 4
    }
}
