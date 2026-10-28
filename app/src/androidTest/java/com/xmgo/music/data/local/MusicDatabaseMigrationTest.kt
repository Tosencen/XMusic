package com.xmgo.music.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MIGRATION_1_2 迁移测试。
 *
 * v1 的 schema JSON 没有导出过（exportSchema 是后来才开的），所以不用
 * `helper.createDatabase(1)`，改为手工按 v1 表结构建库并写入数据，再执行迁移，
 * 用 v2 的导出 schema（schemas/…MusicDatabase/2.json）校验迁移结果：
 * - playlists / playlist_songs 两张表被删除；
 * - local_songs / recent_plays 数据与结构保留。
 */
@RunWith(AndroidJUnit4::class)
class MusicDatabaseMigrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MusicDatabase::class.java
    )

    @Test
    fun migrate1To2_dropsPlaylistTables_keepsOthers() {
        context.deleteDatabase(dbName)
        createV1Database()

        helper.runMigrationsAndValidate(
            dbName, 2, true, MusicDatabase.MIGRATION_1_2
        ).use { db ->
            // 播放清单两张表已删除
            db.query(
                "SELECT COUNT(*) FROM sqlite_master " +
                    "WHERE type='table' AND name IN ('playlists','playlist_songs')"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            // 最近播放数据保留
            db.query("SELECT COUNT(*) FROM recent_plays WHERE songId = 42").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // 本地歌曲数据保留
            db.query("SELECT COUNT(*) FROM local_songs WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
        context.deleteDatabase(dbName)
    }

    /** 按 v1 表结构手工建库（v2 相比 v1 仅删除 playlists / playlist_songs）。 */
    private fun createV1Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS local_songs (" +
                            "id INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, " +
                            "album TEXT NOT NULL, albumId INTEGER NOT NULL, durationMs INTEGER NOT NULL, " +
                            "track INTEGER NOT NULL, year INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, " +
                            "dateAdded INTEGER NOT NULL, uri TEXT NOT NULL, PRIMARY KEY(id))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS recent_plays (" +
                            "songId INTEGER NOT NULL, playedAt INTEGER NOT NULL, PRIMARY KEY(songId))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS playlists (" +
                            "id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(id))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS playlist_songs (" +
                            "id INTEGER NOT NULL, playlistId INTEGER NOT NULL, songId INTEGER NOT NULL, " +
                            "PRIMARY KEY(id))"
                    )
                    db.execSQL(
                        "INSERT INTO local_songs (id, title, artist, album, albumId, durationMs, " +
                            "track, year, sizeBytes, dateAdded, uri) " +
                            "VALUES (7, '测试曲', '歌手', '专辑', 1, 1000, 1, 2024, 100, 1000, 'content://media/external/audio/media/7')"
                    )
                    db.execSQL("INSERT INTO recent_plays (songId, playedAt) VALUES (42, 1000)")
                    db.execSQL("INSERT INTO playlists (id, name) VALUES (1, '我喜欢')")
                    db.execSQL("INSERT INTO playlist_songs (id, playlistId, songId) VALUES (1, 1, 42)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            openHelper.writableDatabase
        } finally {
            openHelper.close()
        }
    }
}
