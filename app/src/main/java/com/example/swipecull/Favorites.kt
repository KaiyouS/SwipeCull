package com.example.swipecull

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Gallery "favorites" live in MediaStore's IS_FAVORITE column (Android 11+). Apps can't set it
 * silently: MediaStore.createFavoriteRequest() shows a system confirmation dialog.
 */
object Favorites {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Converts our folder-picker document URI into the MediaStore *video/image item* URI of the
     * same file. MediaStore.getMediaUri() may hand back an entry in the generic "file" table,
     * which createFavoriteRequest() rejects ("All requested items must be Media items"), so we
     * re-point it at the Video/Images collection (the row id is the same in all of them).
     */
    fun mediaItemUri(context: Context, docUri: Uri, docId: String, isVideo: Boolean): Uri? {
        val candidates = ArrayList<Uri>(2)
        candidates += docUri
        docUri.authority?.let { candidates += DocumentsContract.buildDocumentUri(it, docId) }
        for (c in candidates) {
            val m = runCatching { MediaStore.getMediaUri(context, c) }.getOrNull() ?: continue
            return toItemUri(m, isVideo)
        }
        return null
    }

    private fun toItemUri(m: Uri, isVideo: Boolean): Uri {
        if (m.authority != MediaStore.AUTHORITY) return m
        val segments = m.pathSegments // e.g. [external_primary, file, 1234]
        val collection = segments.getOrNull(1)
        if (collection == "video" || collection == "images") return m // already a proper item
        val id = runCatching { ContentUris.parseId(m) }.getOrNull() ?: return m
        val volume = segments.firstOrNull() ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        val base = if (isVideo) MediaStore.Video.Media.getContentUri(volume)
        else MediaStore.Images.Media.getContentUri(volume)
        return ContentUris.withAppendedId(base, id)
    }

    fun isFavorite(context: Context, docUri: Uri, docId: String, isVideo: Boolean): Boolean {
        if (!supported) return false
        val m = mediaItemUri(context, docUri, docId, isVideo) ?: return false
        return runCatching {
            context.contentResolver.query(
                m, arrayOf(MediaStore.MediaColumns.IS_FAVORITE), null, null, null,
            )?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } ?: false
        }.getOrDefault(false)
    }

    /** Throws with the URI we tried in the message, so a failure can be diagnosed from the toast. */
    fun request(context: Context, docUri: Uri, docId: String, isVideo: Boolean, value: Boolean): PendingIntent {
        val m = mediaItemUri(context, docUri, docId, isVideo)
            ?: error("Android's media database doesn't know this file yet")
        try {
            return MediaStore.createFavoriteRequest(context.contentResolver, listOf(m), value)
        } catch (e: Exception) {
            throw IllegalStateException("${e.message} [$m]", e)
        }
    }
}
