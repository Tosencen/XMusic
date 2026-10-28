package com.xmgo.music.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 在线歌最近播放记录。 */
@Serializable
data class RecentOnlineEntry(
    val song: Song,
    val playedAt: Long = System.currentTimeMillis()
)

/**
 * 在线歌最近播放（DataStore JSON 持久化，key = sourceId:rawId 去重置顶，上限 100）。
 * 本地歌最近播放仍在 Room recent_plays，RecentScreen 合并展示。
 */
class RecentStore(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey("recent_online_json")
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer =
        kotlinx.serialization.builtins.ListSerializer(RecentOnlineEntry.serializer())

    val recents: Flow<List<RecentOnlineEntry>> = dataStore.data.map { prefs ->
        decodeListOrNull(json, prefs[key], listSerializer) ?: emptyList()
    }

    suspend fun record(song: Song) {
        val mediaId = FavoritesStore.mediaIdOf(song)
        dataStore.edit { prefs ->
            val current = decodeListOrNull(json, prefs[key], listSerializer)
                ?: return@edit // 原值损坏：不覆盖，避免最近播放被空列表清掉
            prefs[key] = json.encodeToString(
                listSerializer,
                (listOf(RecentOnlineEntry(song)) +
                    current.filterNot { FavoritesStore.mediaIdOf(it.song) == mediaId }
                    ).take(MAX)
            )
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    companion object {
        const val MAX = 100
    }
}
