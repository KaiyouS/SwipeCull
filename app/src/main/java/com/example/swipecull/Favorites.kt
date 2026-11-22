package com.example.swipecull

import android.app.PendingIntent
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

    /** Converts our folder-picker document URI into the MediaStore URI of the same file. */
    private fun mediaUri(context: Context, docUri: Uri, docId: String): Uri? {
        val candidates = ArrayList<Uri>(2)
        candidates += docUri
        docUri.authority?.let { candidates += DocumentsContract.buildDocumentUri(it, docId) }
        for (c in candidates) {
            val m = runCatching { MediaStore.getMediaUri(context, c) }.getOrNull()
            if (m != null) return m
        }
        return null
    }

    fun isFavorite(context: Context, docUri: Uri, docId: String): Boolean {
        if (!supported) return false
        val m = mediaUri(context, docUri, docId) ?: return false
        return runCatching {
            context.contentResolver.query(
                m, arrayOf(MediaStore.MediaColumns.IS_FAVORITE), null, null, null,
            )?.use { c -> c.moveToFirst() && c.getInt(0) == 1 } ?: false
        }.getOrDefault(false)
    }

    fun request(context: Context, docUri: Uri, docId: String, value: Boolean): PendingIntent {
        val m = mediaUri(context, docUri, docId)
            ?: error("Android's media database doesn't know this file yet")
        return MediaStore.createFavoriteRequest(context.contentResolver, listOf(m), value)
    }
}
