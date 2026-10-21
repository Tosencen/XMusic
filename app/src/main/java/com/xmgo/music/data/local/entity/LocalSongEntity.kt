package com.xmgo.music.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 本地歌曲（MediaStore 扫描缓存）。id 为 MediaStore Audio _ID。 */
@Entity(tableName = "local_songs")
data class LocalSongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val year: Int,
    val sizeBytes: Long,
    val dateAdded: Long,
    val uri: String
) {
    /** 播放队列使用的 MediaItem mediaId。 */
    val mediaId: String get() = "local:$id"
}
