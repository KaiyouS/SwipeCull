package com.example.swipecull

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tags are private to SwipeCull: a list of tag names, plus which files (by document URI) carry
 * which tags. Nothing is written into the media files themselves.
 */
class TagStore(context: Context) {
    private val prefs = context.getSharedPreferences("tags", Context.MODE_PRIVATE)
    private val lock = Any()

    class Data(val names: List<String>, val assign: Map<String, Set<String>>)

    private class Model(val names: MutableList<String>, val assign: MutableMap<String, MutableSet<String>>)

    private fun read(): Model {
        val names = ArrayList<String>()
        val assign = LinkedHashMap<String, MutableSet<String>>()
        runCatching {
            val a = JSONArray(prefs.getString(KEY_NAMES, "[]"))
            for (i in 0 until a.length()) names += a.getString(i)
            val o = JSONObject(prefs.getString(KEY_ASSIGN, "{}"))
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                val set = LinkedHashSet<String>()
                for (i in 0 until arr.length()) set += arr.getString(i)
                if (set.isNotEmpty()) assign[k] = set
            }
        }
        return Model(names, assign)
    }

    private fun write(m: Model) {
        val o = JSONObject()
        for ((k, v) in m.assign) if (v.isNotEmpty()) o.put(k, JSONArray(v.toList()))
        prefs.edit()
            .putString(KEY_NAMES, JSONArray(m.names).toString())
            .putString(KEY_ASSIGN, o.toString())
            .apply()
    }

    private inline fun <T> edit(block: (Model) -> T): T = synchronized(lock) {
        val m = read()
        val r = block(m)
        write(m)
        r
    }

    fun snapshot(): Data = synchronized(lock) {
        val m = read()
        Data(m.names.toList(), m.assign.mapValues { it.value.toSet() })
    }

    /** Adds a tag name if it's new (case-insensitive). Returns the name as stored, or null if blank. */
    fun addName(raw: String): String? {
        val name = raw.trim().take(MAX_NAME)
        if (name.isEmpty()) return null
        return edit { m ->
            m.names.firstOrNull { it.equals(name, ignoreCase = true) } ?: name.also { m.names += it }
        }
    }

    fun tagsOf(uri: String): Set<String> = synchronized(lock) { read().assign[uri]?.toSet() ?: emptySet() }

    fun toggle(uri: String, tag: String) = edit { m ->
        val set = m.assign.getOrPut(uri) { LinkedHashSet() }
        if (!set.remove(tag)) set.add(tag)
        if (set.isEmpty()) m.assign.remove(uri)
    }

    fun tag(uri: String, tag: String) = edit { m -> m.assign.getOrPut(uri) { LinkedHashSet() }.add(tag) }

    fun untag(uri: String, tag: String) = edit { m ->
        m.assign[uri]?.let { it.remove(tag); if (it.isEmpty()) m.assign.remove(uri) }
    }

    fun setTags(uri: String, tags: Set<String>) = edit { m ->
        if (tags.isEmpty()) m.assign.remove(uri) else m.assign[uri] = LinkedHashSet(tags)
    }

    fun removeFile(uri: String) = edit { m -> m.assign.remove(uri) }

    /** The file moved: carry its tags over to its new URI. */
    fun rename(old: String, new: String) = edit { m ->
        if (old != new) m.assign.remove(old)?.let { m.assign[new] = it }
    }

    fun deleteTag(tag: String) = edit { m ->
        m.names.removeAll { it == tag }
        val it = m.assign.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.remove(tag)
            if (e.value.isEmpty()) it.remove()
        }
    }

    fun urisWith(tag: String): List<String> = synchronized(lock) {
        read().assign.filterValues { tag in it }.keys.toList()
    }

    private companion object {
        const val KEY_NAMES = "names"
        const val KEY_ASSIGN = "assign"
        const val MAX_NAME = 30
    }
}
