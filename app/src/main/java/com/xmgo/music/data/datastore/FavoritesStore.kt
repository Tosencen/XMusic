package com.xmgo.music.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 收藏的歌曲（DataStore JSON 持久化，key = sourceId:rawId）。
 * 收藏页点击可直接经插件 resolveStream 播放。
 */
class FavoritesStore(private val dataStore: DataStore<Preferences>) {

    private val key = stringPreferencesKey("favorites_json")
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = kotlinx.serialization.builtins.ListSerializer(Song.serializer())

    val favorites: Flow<List<Song>> = dataStore.data.map { prefs ->
        decodeListOrNull(json, prefs[key], listSerializer) ?: emptyList()
    }

    suspend fun setFavorite(song: Song, favorite: Boolean) {
        dataStore.edit { prefs ->
            val current = decodeListOrNull(json, prefs[key], listSerializer)
                ?: return@edit // 原值损坏：不覆盖，避免用户收藏被空列表清掉
            val target = mediaIdOf(song)
            val next = if (favorite) {
                if (current.any { mediaIdOf(it) == target }) current else current + song
            } else {
                current.filterNot { mediaIdOf(it) == target }
            }
            prefs[key] = json.encodeToString(listSerializer, next)
        }
    }

    companion object {
        fun mediaIdOf(song: Song): String = "${song.sourceId}:${song.rawId}"
    }
}

/**
 * 解码 DataStore 中的 JSON 列表。
 * 返回 null 表示"原值存在但解码失败"——写入方必须保留原 raw 不覆盖，
 * 否则一次字段不兼容会让用户数据被空列表永久清掉。
 */
internal fun <T> decodeListOrNull(
    json: Json,
    raw: String?,
    serializer: kotlinx.serialization.DeserializationStrategy<List<T>>
): List<T>? {
    if (raw == null) return emptyList()
    return runCatching { json.decodeFromString(serializer, raw) }
        .onFailure {
            android.util.Log.w("DataStore", "列表解码失败，保留原值不覆盖", it)
        }
        .getOrNull()
}

/** 在线 MediaItem（mediaId = sourceId:rawId）→ 可收藏/可再播放的 [Song]。 */
fun MediaItem.toSongOrNull(): Song? {
    val mid = mediaId
    if (!mid.contains(':')) return null
    val title = mediaMetadata.title?.toString() ?: return null
    return Song(
        id = mid,
        name = title,
        artists = mediaMetadata.artist?.toString()
            ?.split(" / ")?.filter { it.isNotBlank() } ?: emptyList(),
        album = mediaMetadata.albumTitle?.toString(),
        durationMs = mediaMetadata.durationMs
            ?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        artworkUrl = mediaMetadata.artworkUri?.toString(),
        sourceId = mid.substringBefore(':'),
        rawId = mid.substringAfter(':')
    )
}
