package com.xmgo.music.playback

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.xmgo.music.data.repository.MediaStoreRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.playbackDataStore by preferencesDataStore(name = "playback_state")

@Serializable
data class PersistedItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUri: String? = null,
    /** 时长一并持久化：重启恢复时流地址已失效、prepare 失败拿不到 duration，
     *  没有它播放页 Slider 会被 disabled、波浪进度恒 0。 */
    val durationMs: Long = 0
)

@Serializable
data class PersistedQueue(
    val items: List<PersistedItem> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false
)

/**
 * 播放队列/进度持久化：杀进程重启后恢复队列与播放位置。
 * 写入为节流后的 JSON，读取在首次连接时执行一次。
 */
class PlaybackStateStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("queue_json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _pending = MutableStateFlow<PersistedQueue?>(null)
    val pending: StateFlow<PersistedQueue?> = _pending.asStateFlow()

    suspend fun load(): PersistedQueue? {
        val raw = context.playbackDataStore.data.first()[key] ?: return null
        return runCatching { json.decodeFromString<PersistedQueue>(raw) }.getOrNull()
    }

    fun save(queue: PersistedQueue) {
        _pending.value = queue
        scope.launch {
            context.playbackDataStore.edit { it[key] = json.encodeToString(queue) }
        }
    }

    companion object {
        fun MediaItem.toPersisted(): PersistedItem = PersistedItem(
            mediaId = mediaId,
            uri = localConfiguration?.uri?.toString() ?: "",
            title = mediaMetadata.title?.toString() ?: "",
            artist = mediaMetadata.artist?.toString() ?: mediaMetadata.albumArtist?.toString() ?: "",
            album = mediaMetadata.albumTitle?.toString(),
            artworkUri = mediaMetadata.artworkUri?.toString(),
            durationMs = mediaMetadata.durationMs
                ?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
        )

        fun PersistedItem.toMediaItem(): MediaItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri?.let(android.net.Uri::parse) ?: DEFAULT_ARTWORK_URI)
                    // setDurationMs 只接受 null 或 >=0，TIME_UNSET(负数) 会抛 IllegalArgumentException
                    .setDurationMs(if (durationMs > 0) durationMs else null)
                    .build()
            )
            .build()
    }
}
