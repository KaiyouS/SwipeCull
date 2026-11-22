package com.example.swipecull

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Files you've sent to the "bin" but that haven't been moved to the system recycle bin yet.
 * Android asks for one confirmation per batch, so they're queued here until you send them.
 */
class BinStore(context: Context) {
    private val prefs = context.getSharedPreferences("bin", Context.MODE_PRIVATE)
    private val lock = Any()

    class Item(val uri: String, val video: Boolean)

    private fun read(): MutableList<Item> {
        val out = ArrayList<Item>()
        runCatching {
            val a = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Item(o.getString("u"), o.optBoolean("v"))
            }
        }
        return out
    }

    private fun write(items: List<Item>) {
        val a = JSONArray()
        for (it in items) a.put(JSONObject().put("u", it.uri).put("v", it.video))
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    fun items(): List<Item> = synchronized(lock) { read() }

    fun add(uri: String, video: Boolean) = synchronized(lock) {
        val l = read()
        if (l.none { it.uri == uri }) {
            l += Item(uri, video)
            write(l)
        }
    }

    fun remove(uri: String) = synchronized(lock) {
        val l = read()
        if (l.removeAll { it.uri == uri }) write(l)
    }

    fun removeAll(uris: Collection<String>) = synchronized(lock) {
        val set = uris.toSet()
        val l = read()
        if (l.removeAll { it.uri in set }) write(l)
    }

    fun clear() = synchronized(lock) { prefs.edit().remove(KEY).apply() }

    /** Document ids of queued files, so folder listings can leave them out. */
    fun docIds(): Set<String> = items().mapNotNull {
        runCatching { DocumentsContract.getDocumentId(Uri.parse(it.uri)) }.getOrNull()
    }.toSet()

    private companion object {
        const val KEY = "items"
    }
}
