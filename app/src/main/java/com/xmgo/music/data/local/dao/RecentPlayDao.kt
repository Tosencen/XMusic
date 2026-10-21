package com.xmgo.music.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.xmgo.music.data.local.entity.LocalSongEntity
import com.xmgo.music.data.local.entity.RecentPlayEntity
import kotlinx.coroutines.flow.Flow

/** 最近播放的歌曲（带播放时间）。 */
data class RecentWithSong(
    @Embedded val song: LocalSongEntity,
    val playedAt: Long
)

/** 最近播放记录 DAO。 */
@Dao
interface RecentPlayDao {
    @Query(
        """
        SELECT local_songs.*, recent_plays.playedAt AS playedAt
        FROM recent_plays
        INNER JOIN local_songs ON local_songs.id = recent_plays.songId
        ORDER BY recent_plays.playedAt DESC
        LIMIT :limit
        """
    )
    fun observeRecentSongs(limit: Int = 100): Flow<List<RecentWithSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordPlay(entity: RecentPlayEntity)

    @Transaction
    suspend fun clearRecents() {
        deleteAllRecents()
    }

    @Query("DELETE FROM recent_plays")
    suspend fun deleteAllRecents()
}
