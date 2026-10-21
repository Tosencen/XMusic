package com.xmgo.music.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 搜索历史（DataStore JSON 持久化，去重置顶，上限 20 条）。
 */
class SearchHistoryStore(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey("search_history_json")
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    val history: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun record(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString(listSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[key] = json.encodeToString(
                listSerializer,
                (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
            )
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    companion object {
        const val MAX = 20
    }
}
