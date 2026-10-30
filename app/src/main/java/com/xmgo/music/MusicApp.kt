package com.xmgo.music

import android.app.Application
import com.xmgo.music.di.appModule
import com.xmgo.music.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // 网盘音源：登录态/主音源恢复（EncryptedSharedPreferences）
        com.xmgo.music.playback.CloudSourceManager.init(this)
        // 悬浮歌词：注册设置监听 + 切歌歌词预载（未开启时仅 collect 设置，无窗口）
        KoinPlatform.getKoin().get<com.xmgo.music.overlay.OverlayLyricManager>()
        // 应用自更新：启动 5s 后静默查一次（每进程一次；断网/失败静默，有新版经全局弹窗提示）
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            delay(5_000)
            val updater = KoinPlatform.getKoin().get<com.xmgo.music.update.AppUpdater>()
            runCatching { updater.check() }.getOrNull()?.let(updater::offer)
        }
    }
}
