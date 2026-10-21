package com.xmgo.music

import android.app.Application
import com.xmgo.music.di.appModule
import com.xmgo.music.plugin.PluginManager
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform

class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@MusicApp)
            modules(appModule)
        }
        // 恢复已安装的 .qplug 插件（幂等）
        KoinPlatform.getKoin().get<PluginManager>().init()
        // 悬浮歌词：注册设置监听 + 切歌歌词预载（未开启时仅 collect 设置，无窗口）
        KoinPlatform.getKoin().get<com.xmgo.music.overlay.OverlayLyricManager>()
    }
}
