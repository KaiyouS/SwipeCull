package com.example.swipecull

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Finds bursts and near-duplicate photos: shots taken within two minutes of each other whose
 * tiny 9x8 "difference hash" fingerprints are nearly identical. Photos with no neighbour in
 * time are never decoded, so folders of unrelated pictures stay quick.
 */
object SimilarPhotos {
    private const val WINDOW_MS = 120_000L
    private const val MAX_DISTANCE = 10 // out of 64 bits

    /** Returns the files in the same order, with each group of similar photos made adjacent. */
    suspend fun group(context: Context, files: List<MediaFile>): List<List<MediaFile>> = coroutineScope {
        val photos = files.filter { !it.isVideo && it.lastModified > 0 }.sortedBy { it.lastModified }
        val candidate = BooleanArray(photos.size)
        for (i in 1 until photos.size) {
            if (photos[i].lastModified - photos[i - 1].lastModified <= WINDOW_MS) {
                candidate[i] = true
                candidate[i - 1] = true
            }
        }

        val gate = Semaphore(4)
        val hashes = arrayOfNulls<Long>(photos.size)
        photos.indices.filter { candidate[it] }.map { i ->
            async(Dispatchers.IO) {
                gate.withPermit { hashes[i] = dHash(context, photos[i]) }
            }
        }.awaitAll()

        val parent = IntArray(photos.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        for (i in photos.indices) {
            val hi = hashes[i] ?: continue
            var j = i - 1
            while (j >= 0 && photos[i].lastModified - photos[j].lastModified <= WINDOW_MS) {
                val hj = hashes[j]
                if (hj != null && java.lang.Long.bitCount(hi xor hj) <= MAX_DISTANCE) {
                    parent[find(i)] = find(j)
                }
                j--
            }
        }

        val rootOf = HashMap<String, Int>()
        val members = HashMap<Int, ArrayList<String>>()
        for (i in photos.indices) {
            val r = find(i)
            members.getOrPut(r) { ArrayList() } += photos[i].documentId
        }
        for ((r, ids) in members) if (ids.size > 1) ids.forEach { rootOf[it] = r }

        val byRoot = HashMap<Int, ArrayList<MediaFile>>()
        for (f in files) rootOf[f.documentId]?.let { byRoot.getOrPut(it) { ArrayList() } += f }

        val out = ArrayList<List<MediaFile>>()
        val emitted = HashSet<Int>()
        for (f in files) {
            val r = rootOf[f.documentId]
            if (r == null) out += listOf(f)
            else if (emitted.add(r)) out += byRoot.getValue(r)
        }
        out
    }

    private fun dHash(context: Context, f: MediaFile): Long? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(f.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 64) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(f.uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val small = Bitmap.createScaledBitmap(bmp, 9, 8, true)
        if (small !== bmp) bmp.recycle()
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                hash = hash shl 1
                if (luma(small.getPixel(x, y)) < luma(small.getPixel(x + 1, y))) hash = hash or 1L
            }
        }
        small.recycle()
        hash
    }.getOrNull()

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
