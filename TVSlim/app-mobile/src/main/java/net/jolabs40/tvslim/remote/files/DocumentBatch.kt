package net.jolabs40.tvslim.remote.files

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import net.jolabs40.tvslim.files.LocalFile
import net.jolabs40.tvslim.files.LocalBatch
import java.io.IOException
import java.io.InputStream

/** A document from the Android picker, read from its provider at upload time; nothing is copied beforehand. */
private class PickedDocument(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val path: String,
    override val size: Long,
    override val date: Long,
) : LocalFile {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Document illisible : $path")
}

/**
 * Builds a batch from individually picked documents. Two documents from different folders may share a name;
 * the second gets " (2)" so it does not overwrite the first on the TV.
 */
fun documentBatch(context: Context, documents: List<Uri>): LocalBatch {
    val resolver = context.contentResolver
    val taken = mutableSetOf<String>()
    val files = documents.distinct().map { uri ->
        var name = "document"
        var size = 0L
        var date = 0L
        // All columns: a provider without a last-modified column would reject an explicit projection.
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.text(OpenableColumns.DISPLAY_NAME)?.let { name = it }
                size = cursor.longOrZero(OpenableColumns.SIZE)
                date = cursor.longOrZero(Document.COLUMN_LAST_MODIFIED)
            }
        }
        PickedDocument(resolver, uri, availableName(name, taken), size, date)
    }
    return LocalBatch(files)
}

/** Walks a picked folder tree: files with their relative paths, plus every folder, empty ones included. */
fun folderBatch(context: Context, tree: Uri): LocalBatch {
    val resolver = context.contentResolver
    val root = DocumentsContract.getTreeDocumentId(tree)
    val name = folderName(context, tree)
    val files = mutableListOf<LocalFile>()
    val folders = mutableListOf<String>()
    walk(resolver, tree, root, name, files, folders, depth = 0)
    return LocalBatch(files, folders)
}

/** Returns a document's display name as given by its provider. */
fun documentName(context: Context, document: Uri): String =
    context.contentResolver.query(document, null, null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.text(OpenableColumns.DISPLAY_NAME) else null }
        ?: document.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: "document"

/** Returns a picked folder's display name, or the end of its document ID. */
fun folderName(context: Context, tree: Uri): String {
    val root = DocumentsContract.getTreeDocumentId(tree)
    return context.contentResolver
        .query(DocumentsContract.buildDocumentUriUsingTree(tree, root), null, null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.text(Document.COLUMN_DISPLAY_NAME) else null }
        ?: root.substringAfterLast(':').substringAfterLast('/').ifBlank { "dossier" }
}

private fun walk(
    resolver: ContentResolver,
    tree: Uri,
    document: String,
    path: String,
    files: MutableList<LocalFile>,
    folders: MutableList<String>,
    depth: Int,
) {
    folders += path
    if (depth >= MAX_DEPTH) return
    val subfolders = mutableListOf<Pair<String, String>>()
    val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )
    resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, document), columns, null, null, null)
        ?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val subPath = "$path/$name"
                if (cursor.getString(2) == Document.MIME_TYPE_DIR) {
                    subfolders += id to subPath
                } else {
                    files += PickedDocument(
                        resolver = resolver,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        path = subPath,
                        size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        date = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                    )
                }
            }
        }
    // Recurse after closing the cursor, so a deep tree does not keep one cursor open per level.
    subfolders.sortedBy { it.second.lowercase() }.forEach { (id, subPath) ->
        walk(resolver, tree, id, subPath, files, folders, depth + 1)
    }
}

private fun availableName(name: String, taken: MutableSet<String>): String {
    var free = name
    var index = 2
    while (!taken.add(free)) {
        val point = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        free = "${name.substring(0, point)} ($index)${name.substring(point)}"
        index++
    }
    return free
}

private fun Cursor.text(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.longOrZero(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

/** Guards against a provider exposing a cyclic tree. */
private const val MAX_DEPTH = 64
