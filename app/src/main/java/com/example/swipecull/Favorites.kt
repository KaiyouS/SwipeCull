package com.example.swipecull

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * Gallery "favorites" live in MediaStore's IS_FAVORITE column (Android 11+). Apps can't set it
 * silently: MediaStore.createFavoriteRequest() shows a system confirmation dialog.
 *
 * Reading favorites for files outside the app's own folder grants needs the photos & videos
 * permission. Without it we fall back to MediaStore.getMediaUri(), which works from our
 * folder-picker access but is less reliable.
 */
object Favorites {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    val mediaPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasMediaAccess(context: Context): Boolean = mediaPermissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** One favorite as listed by MediaStore. */
    class Row(val volume: String, val relativePath: String, val name: String, val video: Boolean) {
        /** The matching DocumentsProvider id, e.g. "primary:DCIM/Camera/clip.mp4". */
        val docId: String
            get() {
                val v = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) "primary" else volume.uppercase()
                return "$v:$relativePath$name"
            }
    }

    /** Every photo and video MediaStore has flagged as a favorite. Needs [hasMediaAccess]. */
    fun queryFavorites(context: Context): List<Row> {
        val rows = ArrayList<Row>()
        val collections = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to false,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to true,
        )
        for ((uri, video) in collections) {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.VOLUME_NAME,
                ),
                "${MediaStore.MediaColumns.IS_FAVORITE} = 1",
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    rows += Row(
                        volume = c.getString(2) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                        relativePath = c.getString(1) ?: "",
                        name = name,
                        video = video,
                    )
                }
            }
        }
        return rows
    }

    /** ("external_primary", "DCIM/Camera/", "clip.mp4") from "primary:DCIM/Camera/clip.mp4". */
    private fun pathParts(docId: String): Triple<String, String, String>? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val vol = docId.substring(0, colon)
        val path = docId.substring(colon + 1)
        val slash = path.lastIndexOf('/')
        if (slash < 0) return null
        val volume = if (vol == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY else vol.lowercase()
        return Triple(volume, path.substring(0, slash + 1), path.substring(slash + 1))
    }

    /** Looks the file up in MediaStore by folder + name (needs the photos & videos permission). */
    private fun findByPath(context: Context, docId: String, isVideo: Boolean): Uri? {
        val (volume, rel, name) = pathParts(docId) ?: return null
        val base = runCatching {
            if (isVideo) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Images.Media.getContentUri(volume)
        }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(rel, name),
                null,
            )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(base, c.getLong(0)) else null }
        }.getOrNull()
    }

    /**
     * The MediaStore *video/image item* URI of the file behind our folder-picker URI.
     * Tries a direct path lookup first, then MediaStore.getMediaUri().
     */
    fun mediaItemUri(context: Context, docUri: Uri, docId: String, isVideo: Boolean): Uri? {
        findByPath(context, docId, isVideo)?.let { return it }

        val candidates = ArrayList<Uri>(2)
        candidates += docUri
        docUri.authority?.let { candidates += DocumentsContract.buildDocumentUri(it, docId) }
        for (c in candidates) {
            val m = runCatching { MediaStore.getMediaUri(context, c) }.getOrNull() ?: continue
            return toItemUri(m, isVideo)
        }
        return null
    }

    /**
     * getMediaUri() may hand back an entry in the generic "file" table, which
     * createFavoriteRequest() rejects ("All requested items must be Media items"), so re-point
     * it at the Video/Images collection (the row id is the same in all of them).
     */
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
