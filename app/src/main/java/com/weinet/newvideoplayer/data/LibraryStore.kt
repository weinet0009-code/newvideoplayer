package com.weinet.newvideoplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Recently played videos and user playlists, kept as JSON in SharedPreferences. */
object LibraryStore {
    private const val MAX_RECENT = 50
    private lateinit var prefs: SharedPreferences

    private val _recent = MutableStateFlow<List<Long>>(emptyList())
    val recent: StateFlow<List<Long>> = _recent

    private val _playlists = MutableStateFlow<Map<String, List<Long>>>(emptyMap())
    val playlists: StateFlow<Map<String, List<Long>>> = _playlists

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)
        _recent.value = JSONArray(prefs.getString("recent", "[]")).let { a -> List(a.length()) { a.getLong(it) } }
        val obj = JSONObject(prefs.getString("playlists", "{}")!!)
        _playlists.value = obj.keys().asSequence().associateWith { k ->
            obj.getJSONArray(k).let { a -> List(a.length()) { a.getLong(it) } }
        }.toSortedMap()
    }

    fun addRecent(id: Long) {
        _recent.value = (listOf(id) + _recent.value.filter { it != id }).take(MAX_RECENT)
        prefs.edit().putString("recent", JSONArray(_recent.value).toString()).apply()
    }

    fun clearRecent() {
        _recent.value = emptyList()
        prefs.edit().putString("recent", "[]").apply()
    }

    fun createPlaylist(name: String) {
        if (name.isBlank() || _playlists.value.containsKey(name)) return
        save(_playlists.value + (name to emptyList()))
    }

    fun deletePlaylist(name: String) = save(_playlists.value - name)

    fun addToPlaylist(name: String, id: Long) {
        val cur = _playlists.value[name] ?: emptyList()
        if (id !in cur) save(_playlists.value + (name to cur + id))
    }

    fun removeFromPlaylist(name: String, id: Long) {
        val cur = _playlists.value[name] ?: return
        save(_playlists.value + (name to cur - id))
    }

    private fun save(map: Map<String, List<Long>>) {
        _playlists.value = map.toSortedMap()
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, JSONArray(v)) }
        prefs.edit().putString("playlists", obj.toString()).apply()
    }
}
