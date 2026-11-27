package com.example.swipecull

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
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
    private val bin = BinStore(app)
    private val prefs = app.getSharedPreferences("swipecull", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** System "add to favorites?" dialogs the UI should show. */
    private val _favRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val favRequests: SharedFlow<IntentSender> = _favRequests.asSharedFlow()
    @Volatile private var pendingFav: Pair<String, Boolean>? = null

    /** The system "move these to the bin?" confirmation the UI should show. */
    private val _trashRequests = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val trashRequests: SharedFlow<IntentSender> = _trashRequests.asSharedFlow()
    @Volatile private var pendingTrash: List<BinStore.Item>? = null

    private class Session(
        val source: Uri,
        val sourceParent: Uri,
        @Volatile var destParent: Uri?,
        val customDest: Boolean,
        val keepParent: Uri?,
        val keepVideosOnly: Boolean,
        /** True when re-reviewing tagged files, which can live in different folders. */
        val tagReview: Boolean = false,
    )

    private enum class Kind { DELETE, KEEP_MOVE, KEEP_STAY, SKIP, BIN }

    private class Action(val index: Int, val file: MediaFile, val kind: Kind) {
        @Volatile var movedUri: Uri? = null
        /** Folder the file was in before it was moved (where undo puts it back). */
        @Volatile var origParent: Uri? = null
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
        _state.update {
            it.copy(
                shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
                levelVolume = prefs.getBoolean(KEY_LEVEL, true),
                sortOrder = SortOrder.entries.firstOrNull { o -> o.name == prefs.getString(KEY_SORT, null) } ?: SortOrder.NEWEST,
                typeFilter = TypeFilter.entries.firstOrNull { t -> t.name == prefs.getString(KEY_TYPE, null) } ?: TypeFilter.ALL,
                minSizeMb = prefs.getInt(KEY_MIN_MB, 0),
                olderThanDays = prefs.getInt(KEY_OLDER_DAYS, 0),
                groupSimilar = prefs.getBoolean(KEY_GROUP, false),
                swipeBrowse = prefs.getBoolean(KEY_BROWSE, false),
                seekSeconds = prefs.getInt(KEY_SEEK, 10),
            )
        }
        refreshBin()

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

    /**
     * Turns random order on/off. Mid-session it reshuffles only the files still ahead of you
     * (turning it off puts them back newest-first); the file on screen stays put.
     */
    fun toggleShuffle() {
        val on = !_state.value.shuffle
        prefs.edit().putBoolean(KEY_SHUFFLE, on).apply()
        _state.update { st ->
            val headEnd = maxOf(st.frontier, st.index + 1)
            val head = st.items.take(headEnd)
            val tail = st.items.drop(headEnd)
            st.copy(
                shuffle = on,
                items = head + if (on) tail.shuffled() else sortFiles(tail, st.sortOrder),
            )
        }
    }

    fun setSort(o: SortOrder) {
        prefs.edit().putString(KEY_SORT, o.name).apply()
        _state.update { it.copy(sortOrder = o) }
    }

    fun setTypeFilter(t: TypeFilter) {
        prefs.edit().putString(KEY_TYPE, t.name).apply()
        _state.update { it.copy(typeFilter = t) }
    }

    fun setMinSizeMb(v: Int) {
        prefs.edit().putInt(KEY_MIN_MB, v).apply()
        _state.update { it.copy(minSizeMb = v) }
    }

    fun setOlderThanDays(v: Int) {
        prefs.edit().putInt(KEY_OLDER_DAYS, v).apply()
        _state.update { it.copy(olderThanDays = v) }
    }

    fun toggleGroupSimilar() {
        val on = !_state.value.groupSimilar
        prefs.edit().putBoolean(KEY_GROUP, on).apply()
        _state.update { it.copy(groupSimilar = on) }
    }

    fun toggleSwipeBrowse() {
        val on = !_state.value.swipeBrowse
        prefs.edit().putBoolean(KEY_BROWSE, on).apply()
        _state.update { it.copy(swipeBrowse = on) }
    }

    fun setSeekSeconds(v: Int) {
        prefs.edit().putInt(KEY_SEEK, v).apply()
        _state.update { it.copy(seekSeconds = v) }
    }

    // ---- navigation (no file is touched)

    private fun UiState.nextLive(from: Int): Int {
        var i = from
        while (i < items.size && i in gone) i++
        return i
    }

    /** Moves on after acting on the current file, jumping back to where you were if you'd gone back. */
    private fun UiState.advance(moved: Boolean, freed: Long = 0L): UiState {
        val i = index
        val st = if (moved) copy(gone = gone + i) else this
        val ni = st.nextLive(if (i < frontier) frontier else i + 1)
        return st.copy(index = ni, frontier = maxOf(frontier, ni), freedBytes = freedBytes + freed)
    }

    private fun UiState.rewind(a: Int): UiState = copy(index = a, frontier = a, gone = gone - a)

    fun goNext() {
        val s = _state.value
        if (s.current == null || s.undoing) return
        _state.update { st ->
            val ni = st.nextLive(st.index + 1)
            st.copy(index = ni, frontier = maxOf(st.frontier, ni))
        }
    }

    fun goPrevious() {
        if (_state.value.undoing) return
        _state.update { st -> st.previousIndex?.let { st.copy(index = it) } ?: st }
    }

    private fun sortFiles(list: List<MediaFile>, order: SortOrder): List<MediaFile> = when (order) {
        SortOrder.NEWEST -> list.sortedByDescending { it.lastModified }
        SortOrder.OLDEST -> list.sortedBy { it.lastModified }
        SortOrder.LARGEST -> list.sortedByDescending { it.size }
        SortOrder.SMALLEST -> list.sortedBy { it.size }
    }

    /** Applies the type / size / age filters, then the sort (or shuffle) and similar-photo grouping. */
    private suspend fun arrangeItems(
        media: List<MediaFile>,
        cfg: UiState,
    ): Pair<List<MediaFile>, Map<String, String>> {
        val minBytes = cfg.minSizeMb * 1_048_576L
        val cutoff = if (cfg.olderThanDays > 0) {
            System.currentTimeMillis() - cfg.olderThanDays * 86_400_000L
        } else Long.MAX_VALUE
        val filtered = media.filter {
            (cfg.typeFilter == TypeFilter.ALL || (cfg.typeFilter == TypeFilter.VIDEOS) == it.isVideo) &&
                it.size >= minBytes &&
                (it.lastModified <= 0 || it.lastModified <= cutoff)
        }
        val sorted = sortFiles(filtered, cfg.sortOrder)
        if (!cfg.groupSimilar || cfg.typeFilter == TypeFilter.VIDEOS) {
            return (if (cfg.shuffle) sorted.shuffled() else sorted) to emptyMap()
        }
        var groups = SimilarPhotos.group(appCtx, sorted)
        if (cfg.shuffle) groups = groups.shuffled()
        val labels = HashMap<String, String>()
        for (g in groups) {
            if (g.size > 1) g.forEachIndexed { i, f -> labels[f.documentId] = "Similar ${i + 1} of ${g.size}" }
        }
        return groups.flatten() to labels
    }

    fun setKeepVideosOnly(value: Boolean) {
        _state.update { it.copy(keepVideosOnly = value) }
    }

    fun start() {
        val src = _state.value.sourceUri ?: return
        val dst = _state.value.destUri
        val keep = _state.value.keepUri
        val keepVideosOnly = _state.value.keepVideosOnly
        val cfg = _state.value

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
                markedInFolder = null, favorites = emptySet(), reviewLabel = null,
                skippedCount = 0, binnedCount = 0,
                gone = emptySet(), frontier = 0, freedBytes = 0L, similar = emptyMap(),
            )
        }

        viewModelScope.launch {
            try {
                val (ses, arranged) = withContext(Dispatchers.IO) {
                    val root = storage.treeRoot(src)
                    val destParent = dst?.let { storage.treeRoot(it) }
                        ?: storage.findChildDir(root, DEFAULT_DEST_FOLDER)
                    val keepParent = keep?.let { storage.treeRoot(it) }
                    val media = storage.listMedia(src, kept.get(src) + bin.docIds())
                    Session(src, root, destParent, dst != null, keepParent, keepVideosOnly) to
                        arrangeItems(media, cfg)
                }
                session = ses
                _state.update { it.copy(loading = false, items = arranged.first, similar = arranged.second) }
            } catch (ex: Exception) {
                _state.update { it.copy(sessionActive = false, loading = false) }
                _events.tryEmit("Couldn't open folder: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }
    }

    fun endSession() {
        _state.update {
            it.copy(
                sessionActive = false, loading = false, items = emptyList(), index = 0,
                canUndo = false, reviewLabel = null,
            )
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

    /** The folder a file currently sits in (tagged files can be in different folders). */
    private fun fromParentOf(ses: Session, file: MediaFile): Uri =
        if (ses.tagReview) storage.parentOf(file.uri) else ses.sourceParent

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
            it.advance(true, file.size).copy(movedCount = it.movedCount + 1, canUndo = true)
        }

        jobs.trySend {
            try {
                val dest = ses.ensureDest()
                val from = fromParentOf(ses, file)
                action.origParent = from
                action.movedUri = storage.move(file.uri, file.name, file.mime, from, dest)
            } catch (e: Exception) {
                action.failed = true
                if (action.savedTags.isNotEmpty()) {
                    tags.setTags(file.uri.toString(), action.savedTags)
                    refreshTags()
                }
                _state.update {
                    it.copy(
                        movedCount = it.movedCount - 1, freedBytes = it.freedBytes - file.size,
                        gone = it.gone - action.index,
                    )
                }
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
        // (Tag reviews are driven by tags, not by the "already kept" list.)
        if (!moves && !ses.tagReview) kept.add(ses.source, file.documentId)
        _state.update { it.advance(moves).copy(keptCount = it.keptCount + 1, canUndo = true) }

        if (moves && keepParent != null) {
            jobs.trySend {
                try {
                    val from = fromParentOf(ses, file)
                    action.origParent = from
                    val newUri = storage.move(file.uri, file.name, file.mime, from, keepParent)
                    action.movedUri = newUri
                    tags.rename(file.uri.toString(), newUri.toString())
                    refreshTags()
                } catch (e: Exception) {
                    // Couldn't move it: treat it as an ordinary keep so it doesn't reappear.
                    action.failed = true
                    _state.update { it.copy(gone = it.gone - action.index) }
                    if (!ses.tagReview) kept.add(ses.source, file.documentId)
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

        if (action.kind == Kind.SKIP) {
            _state.update {
                it.rewind(action.index).copy(skippedCount = it.skippedCount - 1, canUndo = hasHistory())
            }
            return
        }

        if (action.kind == Kind.BIN) {
            val uri = action.file.uri.toString()
            bin.remove(uri)
            if (action.savedTags.isNotEmpty()) tags.setTags(uri, action.savedTags)
            refreshTags()
            _state.update {
                it.rewind(action.index).copy(
                    binnedCount = it.binnedCount - 1, freedBytes = it.freedBytes - action.file.size,
                    binCount = bin.items().size, canUndo = hasHistory(),
                )
            }
            return
        }

        if (action.kind == Kind.KEEP_STAY) {
            kept.remove(ses.source, action.file.documentId)
            _state.update {
                it.rewind(action.index).copy(keptCount = it.keptCount - 1, canUndo = hasHistory())
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
                        movedUri, action.file.name, action.file.mime, from, action.origParent ?: ses.sourceParent,
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
                st.rewind(action.index).copy(
                    items = list,
                    // A failed delete-move was already subtracted when it failed.
                    movedCount = if (action.kind == Kind.DELETE && !action.failed) st.movedCount - 1 else st.movedCount,
                    freedBytes = if (action.kind == Kind.DELETE && !action.failed) st.freedBytes - action.file.size else st.freedBytes,
                    keptCount = if (action.kind == Kind.KEEP_MOVE) st.keptCount - 1 else st.keptCount,
                    undoing = false,
                )
            }
        }
    }

    // ---------------------------------------------------------------- skip & bin

    /** Next file without keeping or deleting anything (it will come back next run). */
    fun skip() {
        val s = _state.value
        val file = s.current ?: return
        if (session == null || s.undoing) return
        synchronized(history) { history.addLast(Action(s.index, file, Kind.SKIP)) }
        _state.update { it.advance(false).copy(skippedCount = it.skippedCount + 1, canUndo = true) }
    }

    /**
     * For files you're sure about: queues the file for the system recycle bin and moves on.
     * Nothing is touched yet; [sendBinToTrash] moves the whole queue in one confirmation.
     */
    fun binCurrent() {
        val s = _state.value
        val file = s.current ?: return
        if (session == null || s.undoing) return

        val uri = file.uri.toString()
        val action = Action(s.index, file, Kind.BIN)
        action.savedTags = tags.tagsOf(uri)
        if (action.savedTags.isNotEmpty()) {
            tags.removeFile(uri)
            refreshTags()
        }
        bin.add(uri, file.isVideo)
        synchronized(history) { history.addLast(action) }
        _state.update {
            it.advance(true, file.size).copy(
                binnedCount = it.binnedCount + 1,
                binCount = bin.items().size, canUndo = true,
            )
        }
    }

    private fun refreshBin() {
        val n = bin.items().size
        _state.update { it.copy(binCount = n) }
    }

    fun openBinDialog() {
        refreshBin()
        _state.update { it.copy(showBinDialog = true) }
    }

    fun closeBinDialog() = _state.update { it.copy(showBinDialog = false) }

    /** Takes everything out of the queue without touching the files; they'll be reviewable again. */
    fun forgetBinQueue() {
        bin.clear()
        refreshBin()
        closeBinDialog()
        _events.tryEmit("Removed from the bin queue. They'll show up for review again next run.")
    }

    /** Asks Android (one confirmation for the whole batch) to move the queued files to the recycle bin. */
    fun sendBinToTrash() {
        closeBinDialog()
        if (!Favorites.supported) {
            _events.tryEmit("The bin needs Android 11 or newer")
            return
        }
        val items = bin.items()
        if (items.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ready = ArrayList<BinStore.Item>()
                val uris = ArrayList<Uri>()
                var unknown = 0
                for (item in items) {
                    val doc = Uri.parse(item.uri)
                    if (storage.docInfo(doc) == null) { // already gone
                        bin.remove(item.uri)
                        continue
                    }
                    val media = Favorites.mediaItemUri(appCtx, doc, DocumentsContract.getDocumentId(doc), item.video)
                    if (media == null) unknown++ else { ready += item; uris += media }
                }
                refreshBin()
                if (uris.isEmpty()) {
                    _events.tryEmit(
                        if (unknown > 0) "Android's media database doesn't know these files yet"
                        else "Nothing left to move to the bin",
                    )
                    return@launch
                }
                val pi = try {
                    MediaStore.createTrashRequest(appCtx.contentResolver, uris, true)
                } catch (e: Exception) {
                    throw IllegalStateException("${e.message} [${uris.first()}]", e)
                }
                pendingTrash = ready
                _trashRequests.emit(pi.intentSender)
            } catch (e: Exception) {
                _events.tryEmit("Couldn't send to the bin: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun trashResult(approved: Boolean) {
        val items = pendingTrash ?: return
        pendingTrash = null
        if (!approved) return
        bin.removeAll(items.map { it.uri })
        refreshBin()
        _events.tryEmit("Moved ${items.size} file${if (items.size == 1) "" else "s"} to the bin")
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

    fun renameTag(old: String, newName: String) {
        when (tags.renameTag(old, newName)) {
            RenameResult.OK -> refreshTags()
            RenameResult.EXISTS -> _events.tryEmit("A tag called \"${newName.trim()}\" already exists")
            RenameResult.BLANK -> {}
        }
    }

    /** Reorders the tag list (and so the chips under each photo): delta -1 = up, +1 = down. */
    fun moveTag(tag: String, delta: Int) {
        tags.moveTag(tag, delta)
        refreshTags()
    }

    /** Removes the tag from every file. The files themselves are untouched. */
    fun deleteTag(tag: String) {
        tags.deleteTag(tag)
        refreshTags()
    }

    /**
     * Swipe through just the tagged files again (one tag, or every tagged file when [tag] is
     * null). Uses the same delete / keep folders as a normal run.
     */
    fun startTagReview(tag: String?) {
        val s = _state.value
        val src = s.sourceUri
        if (src == null) {
            _events.tryEmit("Choose a folder to review first (the delete folder lives there by default)")
            return
        }
        val queuedForBin = bin.items().map { it.uri }.toSet()
        val uris = (if (tag == null) tags.snapshot().assign.keys.toList() else tags.urisWith(tag))
            .filter { it !in queuedForBin }
        if (uris.isEmpty()) {
            _events.tryEmit("No tagged files")
            return
        }
        val dst = s.destUri
        val keep = s.keepUri
        val keepVideosOnly = s.keepVideosOnly
        val shuffle = s.shuffle

        synchronized(history) { history.clear() }
        session = null
        _state.update {
            it.copy(
                showTags = false, sessionActive = true, loading = true, items = emptyList(), index = 0,
                movedCount = 0, keptCount = 0, canUndo = false, undoing = false,
                markedInFolder = null, favorites = emptySet(), reviewLabel = tag ?: "All tagged",
                skippedCount = 0, binnedCount = 0,
                gone = emptySet(), frontier = 0, freedBytes = 0L, similar = emptyMap(),
            )
        }

        viewModelScope.launch {
            try {
                val (ses, media) = withContext(Dispatchers.IO) {
                    val root = storage.treeRoot(src)
                    val destParent = dst?.let { storage.treeRoot(it) }
                        ?: storage.findChildDir(root, DEFAULT_DEST_FOLDER)
                    val keepParent = keep?.let { storage.treeRoot(it) }
                    val files = uris.mapNotNull { storage.mediaFileFor(Uri.parse(it)) }
                        .sortedByDescending { it.lastModified }
                    Session(src, root, destParent, dst != null, keepParent, keepVideosOnly, tagReview = true) to
                        if (shuffle) files.shuffled() else files
                }
                session = ses
                _state.update { it.copy(loading = false, items = media) }
            } catch (ex: Exception) {
                _state.update { it.copy(sessionActive = false, loading = false, reviewLabel = null) }
                _events.tryEmit("Couldn't start the review: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }
    }

    /**
     * Swipe through every file the gallery has marked as a favorite (MediaStore IS_FAVORITE),
     * with the usual delete / keep folders. Only files inside folders this app has been given
     * access to can be reviewed; the rest are counted and reported. Needs [Favorites.hasMediaAccess].
     */
    fun toggleLevelVolume() {
        val on = !_state.value.levelVolume
        prefs.edit().putBoolean(KEY_LEVEL, on).apply()
        _state.update { it.copy(levelVolume = on) }
    }

    fun notify(message: String) { _events.tryEmit(message) }

    fun startFavoritesReview() {
        val s = _state.value
        val src = s.sourceUri
        if (src == null) {
            _events.tryEmit("Choose a folder to review first (the delete folder lives there by default)")
            return
        }
        if (!Favorites.supported) {
            _events.tryEmit("Favorites need Android 11 or newer")
            return
        }
        val dst = s.destUri
        val keep = s.keepUri
        val keepVideosOnly = s.keepVideosOnly
        val shuffle = s.shuffle
        val queuedForBin = bin.items().map { it.uri }.toSet()

        synchronized(history) { history.clear() }
        session = null
        _state.update {
            it.copy(
                showTags = false, sessionActive = true, loading = true, items = emptyList(), index = 0,
                movedCount = 0, keptCount = 0, canUndo = false, undoing = false,
                markedInFolder = null, favorites = emptySet(), reviewLabel = "Favorites",
                skippedCount = 0, binnedCount = 0,
                gone = emptySet(), frontier = 0, freedBytes = 0L, similar = emptyMap(),
            )
        }

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val root = storage.treeRoot(src)
                    val destParent = dst?.let { storage.treeRoot(it) }
                        ?: storage.findChildDir(root, DEFAULT_DEST_FOLDER)
                    val keepParent = keep?.let { storage.treeRoot(it) }

                    val rows = Favorites.queryFavorites(appCtx)
                    // Every folder we hold a grant for, so favorites can be mapped to document URIs.
                    val trees = appCtx.contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission && it.isWritePermission }
                        .map { it.uri }
                        .filter { runCatching { DocumentsContract.getTreeDocumentId(it) }.isSuccess }
                    var outside = 0
                    val files = ArrayList<MediaFile>()
                    for (r in rows) {
                        val id = r.docId
                        val tree = trees.firstOrNull { t ->
                            val tid = DocumentsContract.getTreeDocumentId(t)
                            id == tid || id.startsWith(if (tid.endsWith("/")) tid else "$tid/") ||
                                (tid.endsWith(":") && id.startsWith(tid))
                        }
                        if (tree == null) { outside++; continue }
                        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        if (doc.toString() in queuedForBin) continue
                        val f = storage.mediaFileFor(doc)
                        if (f == null) outside++ else files += f
                    }
                    files.sortByDescending { it.lastModified }
                    val ses = Session(src, root, destParent, dst != null, keepParent, keepVideosOnly, tagReview = true)
                    Triple(ses, if (shuffle) files.shuffled() else files, rows.size to outside)
                }
                session = result.first
                _state.update { it.copy(loading = false, items = result.second) }
                val (total, outside) = result.third
                _events.tryEmit(
                    when {
                        total == 0 -> "Android's media database lists no favorites. Samsung Gallery may keep its own favorites list that other apps can't read."
                        outside > 0 -> "$total favorites found, $outside are in folders you haven't given this app access to (or no longer exist)"
                        else -> "$total favorites found"
                    },
                )
            } catch (ex: Exception) {
                _state.update { it.copy(sessionActive = false, loading = false, reviewLabel = null) }
                _events.tryEmit("Couldn't read favorites: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }
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
                    gone = emptySet(),
                    frontier = (st.index - before).coerceAtLeast(0),
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
            val fav = Favorites.isFavorite(appCtx, file.uri, file.documentId, file.isVideo)
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
                val pi = Favorites.request(appCtx, file.uri, file.documentId, file.isVideo, newValue)
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
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_LEVEL = "level_volume"
        const val KEY_SORT = "sort"
        const val KEY_TYPE = "type_filter"
        const val KEY_MIN_MB = "min_mb"
        const val KEY_OLDER_DAYS = "older_days"
        const val KEY_GROUP = "group_similar"
        const val KEY_BROWSE = "swipe_browse"
        const val KEY_SEEK = "seek_seconds"
    }
}
