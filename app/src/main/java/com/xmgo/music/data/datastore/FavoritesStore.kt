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

    val favorites: Flow<List<Song>> = dataStore.data.map { prefs ->
        prefs[key]?.let { raw ->
            runCatching { json.decodeFromString<List<Song>>(raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setFavorite(song: Song, favorite: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let { raw ->
                runCatching { json.decodeFromString<List<Song>>(raw) }.getOrNull()
            } ?: emptyList()
            val target = mediaIdOf(song)
            val next = if (favorite) {
                if (current.any { mediaIdOf(it) == target }) current else current + song
            } else {
                current.filterNot { mediaIdOf(it) == target }
            }
            prefs[key] = json.encodeToString(next)
        }
    }

    companion object {
        fun mediaIdOf(song: Song): String = "${song.sourceId}:${song.rawId}"
    }
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
