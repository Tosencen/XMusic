package com.xmgo.music.presentation.shared_components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.playback.playSongsStaged
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「先解析直链、再交给播放器」的 staged 播放流程状态机。
 *
 * 列表页点击在线歌曲的公共逻辑（置 resolving → playSongsStaged → 失败提示）此前在
 * Home/Favorites/OnlineDetail/Search/Cached 五个 Screen 里逐字重复，这里收敛为一份。
 * 失败提示的展示样式各屏不同（横幅/内联/隐藏），由 UI 侧读 [error] 自行合并展示。
 */
class StagedPlayer internal constructor(
    private val scope: CoroutineScope,
    private val pluginManager: PluginManager,
    private val playerController: PlayerController,
    private val cachedMusicStore: CachedMusicStore,
    private val errorMessage: String
) {
    /** 是否正在解析播放地址（驱动 ResolvingIndicator）。 */
    var resolving by mutableStateOf(false)
        private set

    /** 最近一次播放失败提示；页面自身错误更多（加载/搜索），展示时自行优先级合并。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 起播前的附加动作（如收起键盘、记录搜索历史），在 [playAt] 内最先执行。 */
    var onStart: (() -> Unit)? = null

    fun playAt(songs: List<Song>, index: Int) {
        if (songs.isEmpty()) return
        onStart?.invoke()
        scope.launch {
            resolving = true
            val ok = playSongsStaged(
                songs, index, pluginManager, playerController, cachedMusicStore
            ) {
                resolving = false
            }
            if (!ok) {
                resolving = false
                error = errorMessage
            }
        }
    }
}

/** 见 [StagedPlayer]。默认失败文案对齐历史实现「解析播放地址失败」。 */
@Composable
fun rememberStagedPlayer(
    pluginManager: PluginManager,
    playerController: PlayerController,
    cachedMusicStore: CachedMusicStore,
    errorMessage: String = "解析播放地址失败"
): StagedPlayer {
    val scope = rememberCoroutineScope()
    return remember(scope, pluginManager, playerController, cachedMusicStore, errorMessage) {
        StagedPlayer(scope, pluginManager, playerController, cachedMusicStore, errorMessage)
    }
}
