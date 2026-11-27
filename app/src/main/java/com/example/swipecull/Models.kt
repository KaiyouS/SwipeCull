package com.example.swipecull

import android.net.Uri

data class MediaFile(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val mime: String,
    val lastModified: Long,
    val size: Long,
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

enum class SortOrder(val label: String) {
    NEWEST("Newest"), OLDEST("Oldest"), LARGEST("Largest"), SMALLEST("Smallest")
}

enum class TypeFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

data class UiState(
    // Setup / config
    val sourceUri: Uri? = null,
    val sourceName: String? = null,
    /** null = use the default "_marked_for_delete" subfolder inside the source folder. */
    val destUri: Uri? = null,
    val destName: String? = null,
    /** null = kept files simply stay where they are. */
    val keepUri: Uri? = null,
    val keepName: String? = null,
    /** When a keep folder is set: move only videos (true) or every kept file (false). */
    val keepVideosOnly: Boolean = true,

    // Session
    val sessionActive: Boolean = false,
    val loading: Boolean = false,
    val items: List<MediaFile> = emptyList(),
    val index: Int = 0,
    val movedCount: Int = 0,
    val keptCount: Int = 0,
    val skippedCount: Int = 0,
    /** Files sent to the bin queue during this run. */
    val binnedCount: Int = 0,
    /** Files (from any run) waiting to be moved to the system recycle bin. */
    val binCount: Int = 0,
    val showBinDialog: Boolean = false,
    val canUndo: Boolean = false,
    val undoing: Boolean = false,
    /** Number of files currently sitting in the default marked folder (null = unknown / n/a). */
    val markedInFolder: Int? = null,
    /** Document ids of files known to be favorites in the gallery. */
    val favorites: Set<String> = emptySet(),

    // Tags (app-private labels, used to bulk-move files later)
    val tagNames: List<String> = emptyList(),
    /** File URI string -> tags on that file. */
    val tagAssign: Map<String, Set<String>> = emptyMap(),
    val showTags: Boolean = false,
    /** Go through files in random order. */
    val shuffle: Boolean = false,
    val levelVolume: Boolean = true,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    /** Only show files at least this big (0 = any). */
    val minSizeMb: Int = 0,
    /** Only show files older than this many days (0 = any). */
    val olderThanDays: Int = 0,
    val groupSimilar: Boolean = false,
    /** Swiping left/right goes to the next/previous file instead of deleting/keeping. */
    val swipeBrowse: Boolean = false,
    /** Seconds a double-tap on a video skips (0 = off). */
    val seekSeconds: Int = 10,
    /** Bytes in files marked or binned this run. */
    val freedBytes: Long = 0L,
    /** Indices of files that have been moved away (can't be revisited). */
    val gone: Set<Int> = emptySet(),
    /** Furthest position reached; after acting on an earlier file you jump back here. */
    val frontier: Int = 0,
    /** Document id -> "Similar 2 of 3" for grouped photos. */
    val similar: Map<String, String> = emptyMap(),
    /** Non-null while re-reviewing tagged files: the tag name, or "All tagged". */
    val reviewLabel: String? = null,
    val bulkBusy: Boolean = false,
) {
    val current: MediaFile? get() = items.getOrNull(index)

    /** The closest earlier file that is still where it was, or null. */
    val previousIndex: Int?
        get() {
            var i = minOf(index, items.size) - 1
            while (i >= 0 && i in gone) i--
            return if (i >= 0) i else null
        }
}
