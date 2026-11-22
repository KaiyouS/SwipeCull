package com.example.swipecull

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * All file operations go through the Storage Access Framework, so the app needs no
 * storage permissions: the user grants access to one folder via the system picker.
 */
class MediaStorage(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    fun hasPersistedAccess(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }

    /** Document URI of the folder the user picked. */
    fun treeRoot(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    fun friendlyName(tree: Uri): String {
        val path = DocumentsContract.getTreeDocumentId(tree).substringAfter(':', "")
        return if (path.isEmpty()) "Storage root" else path
    }

    private fun childrenOf(parentDoc: Uri): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(
            parentDoc,
            DocumentsContract.getDocumentId(parentDoc),
        )

    /** Images and videos directly inside the folder (not recursive), newest first. */
    fun listMedia(tree: Uri, skipIds: Set<String>): List<MediaFile> {
        val root = treeRoot(tree)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
        val out = ArrayList<MediaFile>()
        resolver.query(childrenOf(root), projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: id
                val mime = c.getString(2) ?: continue
                if (!(mime.startsWith("image/") || mime.startsWith("video/"))) continue
                if (name.startsWith(".")) continue // .trashed-*, .pending-* etc.
                if (id in skipIds) continue
                out += MediaFile(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id),
                    documentId = id,
                    name = name,
                    mime = mime,
                    lastModified = c.getLong(3),
                    size = c.getLong(4),
                )
            }
        }
        return out.sortedByDescending { it.lastModified }
    }

    fun findChildDir(parentDoc: Uri, name: String): Uri? {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
        )
        resolver.query(childrenOf(parentDoc), projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parentDoc, c.getString(0))
                }
            }
        }
        return null
    }

    fun getOrCreateDir(parentDoc: Uri, name: String): Uri =
        findChildDir(parentDoc, name)
            ?: DocumentsContract.createDocument(resolver, parentDoc, Document.MIME_TYPE_DIR, name)
            ?: error("Could not create folder \"$name\"")

    fun countChildren(parentDoc: Uri): Int {
        var n = 0
        resolver.query(
            childrenOf(parentDoc), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { c -> while (c.moveToNext()) n++ }
        return n
    }

    /** Permanently deletes everything inside [parentDoc]. Returns (deleted, failed). */
    fun deleteChildren(parentDoc: Uri): Pair<Int, Int> {
        val ids = ArrayList<String>()
        resolver.query(
            childrenOf(parentDoc), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { ids += it } }

        var ok = 0
        var failed = 0
        for (id in ids) {
            val doc = DocumentsContract.buildDocumentUriUsingTree(parentDoc, id)
            val deleted = try {
                DocumentsContract.deleteDocument(resolver, doc)
            } catch (e: Exception) {
                false
            }
            if (deleted) ok++ else failed++
        }
        return ok to failed
    }

    /**
     * Moves a document to another folder. Uses the provider's native move (instant, keeps
     * timestamps); falls back to copy + delete if the provider can't (e.g. name clash).
     * Returns the document's new URI.
     */
    fun move(doc: Uri, name: String, mime: String, fromParent: Uri, toParent: Uri): Uri {
        val moved = runCatching {
            DocumentsContract.moveDocument(resolver, doc, fromParent, toParent)
        }.getOrNull()
        // The provider answers with a URI relative to the *source* folder's access grant. Rebuild
        // it under the destination folder's grant so the file can be moved again later (undo,
        // bulk move) even when the destination is a separately chosen folder.
        if (moved != null) {
            return DocumentsContract.buildDocumentUriUsingTree(toParent, DocumentsContract.getDocumentId(moved))
        }
        return copyThenDelete(doc, name, mime, toParent)
    }

    /** Folder that contains [doc]. Works for the path-style ids used by on-device storage. */
    fun parentOf(doc: Uri): Uri {
        val parentId = DocumentsContract.getDocumentId(doc).substringBeforeLast('/', "")
        require(parentId.isNotEmpty()) { "Can't find the folder this file is in" }
        return DocumentsContract.buildDocumentUriUsingTree(doc, parentId)
    }

    /** (name, mime) of a document, or null if it no longer exists. */
    fun docInfo(doc: Uri): Pair<String, String>? = runCatching {
        resolver.query(
            doc, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "file") to (c.getString(1) ?: "application/octet-stream") else null
        }
    }.getOrNull()

    /** Builds a [MediaFile] for a document URI (e.g. a tagged file), or null if it's gone / not media. */
    fun mediaFileFor(doc: Uri): MediaFile? = runCatching {
        resolver.query(
            doc,
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_LAST_MODIFIED,
                Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val mime = c.getString(2) ?: return@use null
            if (!(mime.startsWith("image/") || mime.startsWith("video/"))) return@use null
            MediaFile(
                uri = doc,
                documentId = c.getString(0) ?: DocumentsContract.getDocumentId(doc),
                name = c.getString(1) ?: "file",
                mime = mime,
                lastModified = c.getLong(3),
                size = c.getLong(4),
            )
        }
    }.getOrNull()

    private fun copyThenDelete(src: Uri, name: String, mime: String, toParent: Uri): Uri {
        val dst = DocumentsContract.createDocument(resolver, toParent, mime, name)
            ?: error("Could not create \"$name\" in the destination folder")
        try {
            val input = requireNotNull(resolver.openInputStream(src)) { "Can't read $name" }
            val output = requireNotNull(resolver.openOutputStream(dst, "w")) { "Can't write $name" }
            input.use { i -> output.use { o -> i.copyTo(o, 1 shl 16) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, dst) }
            throw e
        }
        if (!DocumentsContract.deleteDocument(resolver, src)) {
            runCatching { DocumentsContract.deleteDocument(resolver, dst) }
            error("Copied \"$name\" but couldn't remove the original")
        }
        return dst
    }
}

/** Remembers which files you swiped right on, so they don't come back next time. */
class KeptStore(context: Context) {
    private val prefs = context.getSharedPreferences("kept", Context.MODE_PRIVATE)

    fun get(tree: Uri): Set<String> =
        prefs.getStringSet(tree.toString(), emptySet()) ?: emptySet()

    fun add(tree: Uri, id: String) {
        val s = HashSet(get(tree))
        s.add(id)
        prefs.edit().putStringSet(tree.toString(), s).apply()
    }

    fun remove(tree: Uri, id: String) {
        val s = HashSet(get(tree))
        s.remove(id)
        prefs.edit().putStringSet(tree.toString(), s).apply()
    }

    fun clear(tree: Uri) {
        prefs.edit().remove(tree.toString()).apply()
    }
}
