package com.xmgo.music.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.xmgo.music.data.local.MusicDatabase
import com.xmgo.music.data.local.entity.LocalSongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * 本地媒体库：扫描 MediaStore.Audio 并同步进 Room。
 * observeLibrary() 在媒体库变化（增删改）时自动重新扫描。
 */
class MediaStoreRepository(
    private val context: Context,
    private val database: MusicDatabase
) {
    private val dao get() = database.localSongDao()

    @Volatile
    private var lastSyncAt = 0L

    fun observeSongs() = dao.observeAll()

    /**
     * 扫描一次并同步 Room；UI 可直接调用做首扫。
     * 30 秒内已扫过则跳过（force=true 强制），避免每次返回本地页都全量扫 MediaStore。
     */
    suspend fun sync(force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < 30_000) return@withContext
        dao.sync(queryMediaStore())
        lastSyncAt = now
    }

    /** 媒体库变化 → 重新扫描。不含首帧（进页由调用方主动 sync 一次）。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeLibrary(): Flow<Unit> = callbackFlow {
        val resolver: ContentResolver = context.contentResolver
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer
        )
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        .mapLatest { sync(force = true) }
        .flowOn(Dispatchers.IO)

    private fun queryMediaStore(): List<LocalSongEntity> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED
        )
        // 音乐标记的全收；无标记但时长 ≥60s 的（录音/播客）也收
        val selection = "${MediaStore.Audio.Media.DURATION} > 0 AND (" +
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
            "${MediaStore.Audio.Media.DURATION} >= 60000)"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val result = mutableListOf<LocalSongEntity>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result += LocalSongEntity(
                    id = id,
                    title = cursor.getString(titleCol) ?: "未知歌曲",
                    artist = cursor.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "未知歌手",
                    album = cursor.getString(albumCol) ?: "未知专辑",
                    albumId = cursor.getLong(albumIdCol),
                    durationMs = cursor.getLong(durationCol),
                    track = cursor.getInt(trackCol) % 1000,
                    year = cursor.getInt(yearCol),
                    sizeBytes = cursor.getLong(sizeCol),
                    dateAdded = cursor.getLong(dateAddedCol),
                    uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()
                )
            }
        }
        return result
    }

    companion object {
        /** 专辑封面 content uri。 */
        fun albumArtUri(albumId: Long): Uri =
            ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId
            )
    }
}
