package com.example.swipecull

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val DEFAULT_DEST_FOLDER = "_marked_for_delete"

class SwipeViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx: Application = app
    private val storage = MediaStorage(app)
    private val kept = KeptStore(app)
    private val tags = TagStore(app)
    private val prefs = app.getSharedPreferences("swipecull", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** System "add to favorites?" dialogs the UI should show. */
    private val _favRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val favRequests: SharedFlow<IntentSender> = _favRequests.asSharedFlow()
    @Volatile private var pendingFav: Pair<String, Boolean>? = null

    private class Session(
        val source: Uri,
        val sourceParent: Uri,
        @Volatile var destParent: Uri?,
        val customDest: Boolean,
        val keepParent: Uri?,
        val keepVideosOnly: Boolean,
    )

    private enum class Kind { DELETE, KEEP_MOVE, KEEP_STAY }

    private class Action(val index: Int, val file: MediaFile, val kind: Kind) {
        @Volatile var movedUri: Uri? = null
        @Volatile var failed: Boolean = false
        /** Tags the file had when it was sent to the delete folder (restored on undo). */
        @Volatile var savedTags: Set<String> = emptySet()
    }

    @Volatile private var session: Session? = null
    private val history = ArrayDeque<Action>()

    /** All file operations run one-by-one, in order, off the main thread. */
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (job in jobs) runCatching { job() }
        }

        refreshTags()

        val src = prefs.getString(KEY_SOURCE, null)?.let(Uri::parse)
        val dst = prefs.getString(KEY_DEST, null)?.let(Uri::parse)
        val keep = prefs.getString(KEY_KEEP, null)?.let(Uri::parse)
        if (src != null && storage.hasPersistedAccess(src)) {
            val validDst = dst?.takeIf { storage.hasPersistedAccess(it) }
            val validKeep = keep?.takeIf { storage.hasPersistedAccess(it) }
            _state.update {
                it.copy(
                    sourceUri = src,
                    sourceName = storage.friendlyName(src),
                    destUri = validDst,
                    destName = validDst?.let { d -> storage.friendlyName(d) },
                    keepUri = validKeep,
                    keepName = validKeep?.let { k -> storage.friendlyName(k) },
                    keepVideosOnly = prefs.getBoolean(KEY_KEEP_VIDEOS_ONLY, true),
                )
            }
            start() // resume where you left off (already-kept files are skipped)
        }
    }

    // ---------------------------------------------------------------- setup

    private fun grant(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun setSource(uri: Uri) {
        val s = _state.value
        if (uri == s.destUri || uri == s.keepUri) {
            _events.tryEmit("Pick a different folder than the destination folders")
            return
        }
        grant(uri)
        _state.update { it.copy(sourceUri = uri, sourceName = storage.friendlyName(uri)) }
    }

    /** Pass null to go back to the default "_marked_for_delete" subfolder. */
    fun setDest(uri: Uri?) {
        if (uri != null) {
            val s = _state.value
            if (uri == s.sourceUri || uri == s.keepUri) {
                _events.tryEmit("Pick a folder that isn't the one you're reviewing or the keep folder")
                return
            }
            grant(uri)
        }
        _state.update { it.copy(destUri = uri, destName = uri?.let { u -> storage.friendlyName(u) }) }
    }

    /** Pass null so kept files stay where they are. */
    fun setKeep(uri: Uri?) {
        if (uri != null) {
            val s = _state.value
            if (uri == s.sourceUri || uri == s.destUri) {
                _events.tryEmit("Pick a folder that isn't the one you're reviewing or the delete folder")
                return
            }
            grant(uri)
        }
        _state.update { it.copy(keepUri = uri, keepName = uri?.let { u -> storage.friendlyName(u) }) }
    }

    fun setKeepVideosOnly(value: Boolean) {
        _state.update { it.copy(keepVideosOnly = value) }
    }

    fun start() {
        val src = _state.value.sourceUri ?: return
        val dst = _state.value.destUri
        val keep = _state.value.keepUri
        val keepVideosOnly = _state.value.keepVideosOnly

        val e = prefs.edit()
        e.putString(KEY_SOURCE, src.toString())
        if (dst != null) e.putString(KEY_DEST, dst.toString()) else e.remove(KEY_DEST)
        if (keep != null) e.putString(KEY_KEEP, keep.toString()) else e.remove(KEY_KEEP)
        e.putBoolean(KEY_KEEP_VIDEOS_ONLY, keepVideosOnly)
        e.apply()

        synchronized(history) { history.clear() }
        session = null
        _state.update {
            it.copy(
                sessionActive = true, loading = true, items = emptyList(), index = 0,
                movedCount = 0, keptCount = 0, canUndo = false, undoing = false,
                markedInFolder = null, favorites = emptySet(),
            )
        }

        viewModelScope.launch {
            try {
                val (ses, media) = withContext(Dispatchers.IO) {
                    val root = storage.treeRoot(src)
                    val destParent = dst?.let { storage.treeRoot(it) }
                        ?: storage.findChildDir(root, DEFAULT_DEST_FOLDER)
                    val keepParent = keep?.let { storage.treeRoot(it) }
                    Session(src, root, destParent, dst != null, keepParent, keepVideosOnly) to
                        storage.listMedia(src, kept.get(src))
                }
                session = ses
                _state.update { it.copy(loading = false, items = media) }
            } catch (ex: Exception) {
                _state.update { it.copy(sessionActive = false, loading = false) }
                _events.tryEmit("Couldn't open folder: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }
    }

    fun endSession() {
        _state.update {
            it.copy(sessionActive = false, loading = false, items = emptyList(), index = 0, canUndo = false)
        }
    }

    fun resetKept() {
        val ses = session ?: return
        kept.clear(ses.source)
        start()
    }

    // ---------------------------------------------------------------- swiping

    private fun hasHistory() = synchronized(history) { history.isNotEmpty() }

    private fun Session.ensureDest(): Uri =
        destParent ?: storage.getOrCreateDir(sourceParent, DEFAULT_DEST_FOLDER).also { destParent = it }

    /** Swipe left: move the current file into the marked-for-deletion folder. */
    fun markForDeletion() {
        val s = _state.value
        val file = s.current ?: return
        val ses = session ?: return
        if (s.undoing) return

        val action = Action(s.index, file, Kind.DELETE)
        // A file headed for deletion shouldn't linger in tag lists; undo puts its tags back.
        action.savedTags = tags.tagsOf(file.uri.toString())
        if (action.savedTags.isNotEmpty()) {
            tags.removeFile(file.uri.toString())
            refreshTags()
        }
        synchronized(history) { history.addLast(action) }
        _state.update {
            it.copy(index = it.index + 1, movedCount = it.movedCount + 1, canUndo = true)
        }

        jobs.trySend {
            try {
                val dest = ses.ensureDest()
                action.movedUri = storage.move(file.uri, file.name, file.mime, ses.sourceParent, dest)
            } catch (e: Exception) {
                action.failed = true
                if (action.savedTags.isNotEmpty()) {
                    tags.setTags(file.uri.toString(), action.savedTags)
                    refreshTags()
                }
                _state.update { it.copy(movedCount = it.movedCount - 1) }
                _events.tryEmit("Couldn't move ${file.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Swipe right: go to the next file. If a keep folder is set (and this file qualifies),
     * the file is moved there; otherwise it stays where it is.
     */
    fun keep() {
        val s = _state.value
        val file = s.current ?: return
        val ses = session ?: return
        if (s.undoing) return

        val keepParent = ses.keepParent
        val moves = keepParent != null && (!ses.keepVideosOnly || file.isVideo)
        val action = Action(s.index, file, if (moves) Kind.KEEP_MOVE else Kind.KEEP_STAY)

        synchronized(history) { history.addLast(action) }
        if (!moves) kept.add(ses.source, file.documentId)
        _state.update { it.copy(index = it.index + 1, keptCount = it.keptCount + 1, canUndo = true) }

        if (moves && keepParent != null) {
            jobs.trySend {
                try {
                    val newUri = storage.move(file.uri, file.name, file.mime, ses.sourceParent, keepParent)
                    action.movedUri = newUri
                    tags.rename(file.uri.toString(), newUri.toString())
                    refreshTags()
                } catch (e: Exception) {
                    // Couldn't move it: treat it as an ordinary keep so it doesn't reappear.
                    action.failed = true
                    kept.add(ses.source, file.documentId)
                    _events.tryEmit("Couldn't move ${file.name} to the keep folder: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun undo() {
        val s = _state.value
        val ses = session ?: return
        if (s.undoing || s.loading) return
        val action = synchronized(history) { history.removeLastOrNull() } ?: return

        if (action.kind == Kind.KEEP_STAY) {
            kept.remove(ses.source, action.file.documentId)
            _state.update {
                it.copy(index = action.index, keptCount = it.keptCount - 1, canUndo = hasHistory())
            }
            return
        }

        _state.update { it.copy(undoing = true, canUndo = hasHistory()) }
        jobs.trySend {
            var restored = action.file.uri
            try {
                if (!action.failed) {
                    val movedUri = action.movedUri ?: error("File location unknown")
                    val from = (if (action.kind == Kind.DELETE) ses.destParent else ses.keepParent)
                        ?: error("Destination folder missing")
                    restored = storage.move(
                        movedUri, action.file.name, action.file.mime, from, ses.sourceParent,
                    )
                    if (action.kind == Kind.DELETE) {
                        if (action.savedTags.isNotEmpty()) tags.setTags(restored.toString(), action.savedTags)
                    } else {
                        tags.rename(movedUri.toString(), restored.toString())
                    }
                    refreshTags()
                } else if (action.kind == Kind.KEEP_MOVE) {
                    kept.remove(ses.source, action.file.documentId)
                }
            } catch (e: Exception) {
                synchronized(history) { history.addLast(action) }
                _state.update { it.copy(undoing = false, canUndo = true) }
                _events.tryEmit("Couldn't undo: ${e.message ?: e.javaClass.simpleName}")
                return@trySend
            }
            _state.update { st ->
                val list = st.items.toMutableList()
                if (action.index in list.indices) list[action.index] = action.file.copy(uri = restored)
                st.copy(
                    items = list,
                    index = action.index,
                    // A failed delete-move was already subtracted when it failed.
                    movedCount = if (action.kind == Kind.DELETE && !action.failed) st.movedCount - 1 else st.movedCount,
                    keptCount = if (action.kind == Kind.KEEP_MOVE) st.keptCount - 1 else st.keptCount,
                    undoing = false,
                )
            }
        }
    }

    // ---------------------------------------------------------------- tags

    private fun refreshTags() {
        val d = tags.snapshot()
        _state.update { it.copy(tagNames = d.names, tagAssign = d.assign) }
    }

    fun openTags() = _state.update { it.copy(showTags = true) }
    fun closeTags() = _state.update { it.copy(showTags = false) }

    /** Adds/removes an existing tag on the file being reviewed. */
    fun toggleTag(tag: String) {
        val file = _state.value.current ?: return
        tags.toggle(file.uri.toString(), tag)
        refreshTags()
    }

    /** Creates a tag (if new) and puts it on the file being reviewed. */
    fun addTagToCurrent(name: String) {
        val file = _state.value.current ?: return
        val tag = tags.addName(name) ?: return
        tags.tag(file.uri.toString(), tag)
        refreshTags()
    }

    /** Removes the tag from every file. The files themselves are untouched. */
    fun deleteTag(tag: String) {
        tags.deleteTag(tag)
        refreshTags()
    }

    /** Moves every file carrying [tag] into [dest], then drops that tag from them. */
    fun moveTagged(tag: String, dest: Uri) {
        if (_state.value.bulkBusy) return
        val uris = tags.urisWith(tag)
        if (uris.isEmpty()) return
        grant(dest)
        _state.update { it.copy(bulkBusy = true) }

        jobs.trySend {
            var ok = 0
            var failed = 0
            var gone = 0
            val movedAway = HashSet<String>()
            try {
                val destParent = storage.treeRoot(dest)
                val destId = DocumentsContract.getDocumentId(destParent)
                for (u in uris) {
                    val doc = Uri.parse(u)
                    val info = storage.docInfo(doc)
                    if (info == null) { // deleted or moved outside the app
                        tags.removeFile(u)
                        gone++
                        continue
                    }
                    try {
                        val parent = storage.parentOf(doc)
                        if (DocumentsContract.getDocumentId(parent) == destId &&
                            parent.authority == destParent.authority
                        ) {
                            tags.untag(u, tag) // already in that folder
                            continue
                        }
                        val newUri = storage.move(doc, info.first, info.second, parent, destParent)
                        tags.rename(u, newUri.toString())
                        tags.untag(newUri.toString(), tag)
                        movedAway += u
                        ok++
                    } catch (e: Exception) {
                        failed++
                    }
                }
            } catch (e: Exception) {
                _events.tryEmit("Couldn't use that folder: ${e.message ?: e.javaClass.simpleName}")
            }

            synchronized(history) { history.clear() } // earlier undo steps may point at moved files
            _state.update { st ->
                val before = st.items.withIndex().count { (i, f) -> i < st.index && f.uri.toString() in movedAway }
                st.copy(
                    items = st.items.filterNot { it.uri.toString() in movedAway },
                    index = (st.index - before).coerceAtLeast(0),
                    canUndo = false,
                    bulkBusy = false,
                )
            }
            refreshTags()

            val msg = StringBuilder("Moved $ok file${if (ok == 1) "" else "s"}")
            if (failed > 0) msg.append(", $failed failed")
            if (gone > 0) msg.append(", $gone no longer existed")
            _events.tryEmit(msg.toString())
        }
    }

    // ---------------------------------------------------------------- favorites

    /** Looks up whether the current file is already a gallery favorite (to draw the star). */
    fun loadFavoriteStatus() {
        val file = _state.value.current ?: return
        if (!Favorites.supported) return
        viewModelScope.launch(Dispatchers.IO) {
            val fav = Favorites.isFavorite(appCtx, file.uri, file.documentId)
            _state.update {
                it.copy(favorites = if (fav) it.favorites + file.documentId else it.favorites - file.documentId)
            }
        }
    }

    /** Asks Android to favorite / un-favorite the current file (shows a system confirmation). */
    fun toggleFavorite() {
        val file = _state.value.current ?: return
        if (!Favorites.supported) {
            _events.tryEmit("Favorites need Android 11 or newer")
            return
        }
        val newValue = file.documentId !in _state.value.favorites
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pi = Favorites.request(appCtx, file.uri, file.documentId, newValue)
                pendingFav = file.documentId to newValue
                _favRequests.emit(pi.intentSender)
            } catch (e: Exception) {
                _events.tryEmit("Couldn't favorite: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun favoriteResult(approved: Boolean) {
        val (id, value) = pendingFav ?: return
        pendingFav = null
        if (!approved) return
        _state.update {
            it.copy(favorites = if (value) it.favorites + id else it.favorites - id)
        }
        _events.tryEmit(if (value) "Added to favorites" else "Removed from favorites")
    }

    // ---------------------------------------------------------------- marked folder

    fun refreshMarkedCount() {
        val ses = session ?: return
        if (ses.customDest) {
            _state.update { it.copy(markedInFolder = null) }
            return
        }
        jobs.trySend {
            val parent = ses.destParent
                ?: storage.findChildDir(ses.sourceParent, DEFAULT_DEST_FOLDER)?.also { ses.destParent = it }
            val n = parent?.let { storage.countChildren(it) } ?: 0
            _state.update { it.copy(markedInFolder = n) }
        }
    }

    /** Permanently deletes everything in the default marked folder. Not offered for custom destinations. */
    fun deleteMarkedPermanently() {
        val ses = session ?: return
        if (ses.customDest) return
        jobs.trySend {
            val parent = ses.destParent
                ?: storage.findChildDir(ses.sourceParent, DEFAULT_DEST_FOLDER)?.also { ses.destParent = it }
            if (parent == null) {
                _state.update { it.copy(markedInFolder = 0) }
                return@trySend
            }
            val (ok, failed) = storage.deleteChildren(parent)
            synchronized(history) { history.clear() } // deleted files can't be undone
            _state.update { it.copy(markedInFolder = failed, canUndo = false) }
            _events.tryEmit(
                if (failed == 0) "Deleted $ok files" else "Deleted $ok files, $failed couldn't be deleted",
            )
        }
    }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_DEST = "dest"
        const val KEY_KEEP = "keep"
        const val KEY_KEEP_VIDEOS_ONLY = "keep_videos_only"
    }
}
