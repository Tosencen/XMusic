package com.xmgo.music.di

import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.data.datastore.dataStore
import com.xmgo.music.data.local.MusicDatabase
import com.xmgo.music.data.repository.MediaStoreRepository
import com.xmgo.music.lyrics.LyricsRepository
import com.xmgo.music.playback.PlaybackStateStore
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.plugin.PluginManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * 应用级 Koin 模块。
 */
val appModule = module {
    single { PluginManager(androidContext()) }

    single { MusicDatabase.get(androidContext()) }
    single { get<MusicDatabase>().localSongDao() }
    single { get<MusicDatabase>().recentPlayDao() }

    single { MediaStoreRepository(androidContext(), get()) }
    single { com.xmgo.music.data.cache.CachedMusicStore(androidContext(), get()) }
    single { FavoritesStore(androidContext().dataStore) }
    single { com.xmgo.music.data.datastore.RecentStore(androidContext().dataStore) }
    single { com.xmgo.music.data.datastore.SearchHistoryStore(androidContext().dataStore) }
    single { com.xmgo.music.presentation.screens.home.HomeContentStore() }
    single { com.xmgo.music.presentation.screens.online.OnlineDetailCache() }
    single { com.xmgo.music.presentation.screens.search.SearchPageCache() }
    single { com.xmgo.music.playback.PlaybackStateStore(androidContext()) }
    single { LyricsRepository(androidContext(), get(), get()) }
    single { PlayerController(androidContext(), get(), get(), get(), get(), get(), get()) }
    single {
        com.xmgo.music.overlay.OverlayLyricManager(
            androidContext(),
            get(),
            get()
        )
    }
}
