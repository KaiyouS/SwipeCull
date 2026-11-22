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

data class UiState(
    // Setup / config
    val sourceUri: Uri? = null,
    val sourceName: String? = null,
    /** null = use the default "_marked_for_delete" subfolder inside the source folder. */
    val destUri: Uri? = null,
    val destName: String? = null,

    // Session
    val sessionActive: Boolean = false,
    val loading: Boolean = false,
    val items: List<MediaFile> = emptyList(),
    val index: Int = 0,
    val movedCount: Int = 0,
    val keptCount: Int = 0,
    val canUndo: Boolean = false,
    val undoing: Boolean = false,
    /** Number of files currently sitting in the default marked folder (null = unknown / n/a). */
    val markedInFolder: Int? = null,
) {
    val current: MediaFile? get() = items.getOrNull(index)
}
