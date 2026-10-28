package com.xmgo.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xmgo.music.data.local.dao.LocalSongDao
import com.xmgo.music.data.local.dao.RecentPlayDao
import com.xmgo.music.data.local.entity.LocalSongEntity
import com.xmgo.music.data.local.entity.RecentPlayEntity

@Database(
    entities = [
        LocalSongEntity::class,
        RecentPlayEntity::class
    ],
    version = 2,
    // 导出 schema JSON（build.gradle.kts ksp arg room.schemaLocation）：改表结构时
    // 有历史 schema 才能用 MigrationTestHelper 校验迁移兼容性
    exportSchema = true
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun localSongDao(): LocalSongDao
    abstract fun recentPlayDao(): RecentPlayDao

    companion object {
        /** v2：移除播放清单（playlists / playlist_songs 两张表）。 */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS playlist_songs")
                db.execSQL("DROP TABLE IF EXISTS playlists")
            }
        }

        @Volatile
        private var instance: MusicDatabase? = null

        fun get(context: Context): MusicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MusicDatabase::class.java,
                    "music.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
