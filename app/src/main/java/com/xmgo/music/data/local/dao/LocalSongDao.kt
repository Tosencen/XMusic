package com.xmgo.music.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.xmgo.music.data.local.entity.LocalSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSongDao {
    @Query("SELECT * FROM local_songs ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<LocalSongEntity>>

    @Query("SELECT * FROM local_songs WHERE id = :id")
    suspend fun getById(id: Long): LocalSongEntity?

    @Query("SELECT * FROM local_songs WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<LocalSongEntity>

    @Query("SELECT id FROM local_songs")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT COUNT(*) FROM local_songs")
    fun observeCount(): Flow<Int>

    /** 关键词搜索：标题/歌手/专辑模糊匹配。 */
    @Query(
        """
        SELECT * FROM local_songs
        WHERE title LIKE '%' || :q || '%'
           OR artist LIKE '%' || :q || '%'
           OR album LIKE '%' || :q || '%'
        ORDER BY title COLLATE NOCASE
        """
    )
    fun search(q: String): Flow<List<LocalSongEntity>>

    @Upsert
    suspend fun upsertAll(songs: List<LocalSongEntity>)

    @Query("DELETE FROM local_songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** 全量同步：以扫描结果为准，删除消失的、更新新增的。 */
    @Transaction
    suspend fun sync(songs: List<LocalSongEntity>) {
        val freshIds = songs.map { it.id }.toSet()
        getAllIds()
            .filter { it !in freshIds }
            .chunked(500)
            .forEach { deleteByIds(it) }
        if (songs.isNotEmpty()) upsertAll(songs)
    }
}
