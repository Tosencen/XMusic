package com.xmgo.music.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 最近播放记录。 */
@Entity(tableName = "recent_plays")
data class RecentPlayEntity(
    @PrimaryKey val songId: Long,
    val playedAt: Long = System.currentTimeMillis()
)
