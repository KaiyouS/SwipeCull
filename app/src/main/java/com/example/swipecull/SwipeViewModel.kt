package com.example.swipecull

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    private val storage = MediaStorage(app)
    private val kept = KeptStore(app)
    private val prefs = app.getSharedPreferences("swipecull", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private class Session(
        val source: Uri,
        val sourceParent: Uri,
        @Volatile var destParent: Uri?,
        val customDest: Boolean,
    )

    private class Action(val index: Int, val file: MediaFile, val moved: Boolean) {
        @Volatile var movedUri: Uri? = null
        @Volatile var failed: Boolean = false
    }

    @Volatile private var session: Session? = null
    private val history = ArrayDeque<Action>()

    /** All file operations run one-by-one, in order, off the main thread. */
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (job in jobs) runCatching { job() }
        }

        val src = prefs.getString(KEY_SOURCE, null)?.let(Uri::parse)
        val dst = prefs.getString(KEY_DEST, null)?.let(Uri::parse)
        if (src != null && storage.hasPersistedAccess(src)) {
            val validDst = dst?.takeIf { storage.hasPersistedAccess(it) }
            _state.update {
                it.copy(
                    sourceUri = src,
                    sourceName = storage.friendlyName(src),
                    destUri = validDst,
                    destName = validDst?.let { d -> storage.friendlyName(d) },
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
        if (uri == _state.value.destUri) {
            _events.tryEmit("Pick a different folder than the destination")
            return
        }
        grant(uri)
        _state.update { it.copy(sourceUri = uri, sourceName = storage.friendlyName(uri)) }
    }

    /** Pass null to go back to the default "_marked_for_delete" subfolder. */
    fun setDest(uri: Uri?) {
        if (uri != null) {
            if (uri == _state.value.sourceUri) {
                _events.tryEmit("Destination must be different from the folder you're reviewing")
                return
            }
            grant(uri)
        }
        _state.update { it.copy(destUri = uri, destName = uri?.let { u -> storage.friendlyName(u) }) }
    }

    fun start() {
        val src = _state.value.sourceUri ?: return
        val dst = _state.value.destUri

        val e = prefs.edit()
        e.putString(KEY_SOURCE, src.toString())
        if (dst != null) e.putString(KEY_DEST, dst.toString()) else e.remove(KEY_DEST)
        e.apply()

        synchronized(history) { history.clear() }
        session = null
        _state.update {
            it.copy(
                sessionActive = true, loading = true, items = emptyList(), index = 0,
                movedCount = 0, keptCount = 0, canUndo = false, undoing = false, markedInFolder = null,
            )
        }

        viewModelScope.launch {
            try {
                val (ses, media) = withContext(Dispatchers.IO) {
                    val root = storage.treeRoot(src)
                    val destParent = dst?.let { storage.treeRoot(it) }
                        ?: storage.findChildDir(root, DEFAULT_DEST_FOLDER)
                    Session(src, root, destParent, dst != null) to storage.listMedia(src, kept.get(src))
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

        val action = Action(s.index, file, moved = true)
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
                _state.update { it.copy(movedCount = it.movedCount - 1) }
                _events.tryEmit("Couldn't move ${file.name}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Swipe right: leave the file where it is and go to the next one. */
    fun keep() {
        val s = _state.value
        val file = s.current ?: return
        val ses = session ?: return
        if (s.undoing) return

        synchronized(history) { history.addLast(Action(s.index, file, moved = false)) }
        kept.add(ses.source, file.documentId)
        _state.update { it.copy(index = it.index + 1, keptCount = it.keptCount + 1, canUndo = true) }
    }

    fun undo() {
        val s = _state.value
        val ses = session ?: return
        if (s.undoing || s.loading) return
        val action = synchronized(history) { history.removeLastOrNull() } ?: return

        if (!action.moved) {
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
                    val destParent = ses.destParent ?: error("Destination folder missing")
                    restored = storage.move(
                        movedUri, action.file.name, action.file.mime, destParent, ses.sourceParent,
                    )
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
                    movedCount = if (action.failed) st.movedCount else st.movedCount - 1,
                    undoing = false,
                )
            }
        }
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
    }
}
