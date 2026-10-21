package com.xmgo.music.playback

import android.content.ComponentName
import android.util.Log
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.xmgo.music.data.datastore.dataStore
import com.xmgo.music.data.local.dao.RecentPlayDao
import com.xmgo.music.data.datastore.RecentStore
import com.xmgo.music.data.datastore.toSongOrNull
import com.xmgo.music.data.local.entity.LocalSongEntity
import com.xmgo.music.data.local.entity.RecentPlayEntity
import com.xmgo.music.data.repository.MediaStoreRepository
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.lyrics.LyricsRepository
import com.xmgo.music.playback.PlaybackStateStore.Companion.toMediaItem
import com.xmgo.music.playback.PlaybackStateStore.Companion.toPersisted
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * 应用侧播放控制门面：连接 PlayerService 的 MediaController，
 * 暴露 UI 状态流，处理播放队列与进度持久化。
 */
class PlayerController(
    private val context: Context,
    private val stateStore: PlaybackStateStore,
    private val recentPlayDao: RecentPlayDao,
    private val recentStore: RecentStore,
    private val lyricsRepository: LyricsRepository,
    private val cachedMusicStore: CachedMusicStore,
    private val pluginManager: com.xmgo.music.plugin.PluginManager
) {
    data class UiState(
        val connected: Boolean = false,
        val queue: List<MediaItem> = emptyList(),
        val current: MediaItem? = null,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val shuffleEnabled: Boolean = false,
        val repeatMode: Int = Player.REPEAT_MODE_OFF,
        val buffering: Boolean = false
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** 睡眠定时剩余毫秒（0 = 未启用）。 */
    private val _sleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = _sleepRemainingMs.asStateFlow()

    /** 当前播放倍速。 */
    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pollJob: Job? = null
    private var sleepJob: Job? = null
    /** 歌词预取防抖：连按下一首时取消未触发的预取，避免挤占插件单线程 actor。 */
    private var lyricsPrefetchJob: Job? = null
    private var restored = false

    /** 流地址失效自动重解析的防死循环标记（同一曲目 30 秒内只重试一次）。 */
    @Volatile private var lastRetryMediaId: String? = null
    @Volatile private var lastRetryAt = 0L

    private val mainExecutor = Executor { it.run() }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer()
            persist()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.let { item ->
                    ArtworkSeed.extract(context, item.mediaMetadata.artworkUri)
                    scope.launch { recordRecent(item) }
                    // 歌词预取防抖：每次 transition 都预取会让连按下一首时
                    // 「当前+下一首」2 个带网络的 lyrics 调用挤占插件单线程 actor，
                    // 拖慢补队列 resolveStream → NEXT_WINDOW 耗尽后切歌变卡。
                    // 等切歌稳定 [LYRICS_PREFETCH_DEBOUNCE_MS] 后再预取，
                    // 正常听歌打开播放页时歌词依然已就绪。
                    lyricsPrefetchJob?.cancel()
                    lyricsPrefetchJob = scope.launch {
                        delay(LYRICS_PREFETCH_DEBOUNCE_MS)
                        lyricsRepository.prefetch(
                            item.mediaId,
                            title = item.mediaMetadata.title?.toString(),
                            artist = item.mediaMetadata.artist?.toString()
                        )
                        controller?.let { c ->
                            val nextIndex = c.nextMediaItemIndex
                            if (nextIndex != androidx.media3.common.C.INDEX_UNSET &&
                                nextIndex in 0 until c.mediaItemCount
                            ) {
                                c.getMediaItemAt(nextIndex).let { next ->
                                    lyricsRepository.prefetch(
                                        next.mediaId,
                                        title = next.mediaMetadata.title?.toString(),
                                        artist = next.mediaMetadata.artist?.toString()
                                    )
                                }
                            }
                        }
                    }
                }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            handleStreamError(error)
        }
    }

    // 断连监听要在 buildAsync 时经 Builder.setListener 传入（Player.Listener 无此回调）
    private val controllerListener = object : MediaController.Listener {
        // 退后台后服务被系统回收（暂停态无前台通知保护）会断开连接；
        // 不处理则 controller 成僵尸对象——play/pause 静默失效、播放页状态卡死。
        override fun onDisconnected(controller: MediaController) {
            if (this@PlayerController.controller !== controller) return
            this@PlayerController.controller = null
            future = null
            pollJob?.cancel()
            // 保留队列/曲目展示避免闪空，仅标记断连并停播
            _ui.value = _ui.value.copy(connected = false, isPlaying = false, buffering = false)
            // 服务进程重启需要一点时间，稍后重连；连接成功后 restoreIfNeeded 会恢复队列
            scope.launch {
                delay(400)
                connect()
            }
        }
    }

    init {
        // 单例懒创建后必须立即连 PlayerService，否则 playQueue 静默 return（无人调过 connect 的历史 bug）
        // 注意：init 必须在 controllerListener/playerListener 声明之后——Kotlin 属性按声明顺序初始化
        connect()
    }

    /** 幂等连接。首次连接后恢复上次队列与进度。 */
    fun connect() {
        if (controller != null || future != null) return
        val token = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(controllerListener)
            .buildAsync()
        future = f
        f.addListener(
            {
                runCatching {
                    val c = f.get()
                    controller = c
                    c.addListener(playerListener)
                    c.currentMediaItem?.let { ArtworkSeed.extract(context, it.mediaMetadata.artworkUri) }
                    restored = false
                    scope.launch { restoreIfNeeded() }
                    // scope.launch { applySavedPlaybackPrefs() } // 倍速/睡眠定时暂时下线
                    syncFromPlayer()
                    startPolling()
                }.onFailure {
                    future = null
                    // 连接失败（服务尚未拉起等）：稍后重试，避免回到无法控制的僵尸态
                    scope.launch {
                        delay(1_000)
                        connect()
                    }
                }
            },
            mainExecutor
        )
    }

    fun release() {
        // 先置空再 release：MediaController.release() 会触发 onDisconnected，
        // 若引用还在会被误判为意外断连并发起重连
        val c = controller
        controller = null
        val f = future
        future = null
        c?.removeListener(playerListener)
        c?.release()
        f?.let { MediaController.releaseFuture(it) }
        pollJob?.cancel()
        restored = false
        _ui.value = UiState()
    }

    /** 播放整列（本地歌或已取流的在线歌）。 */
    fun playQueue(items: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0) {
        val c = controller
        Log.d("PlayerController", "playQueue n=${items.size} idx=$startIndex controllerNull=${c == null}")
        if (c == null || items.isEmpty()) return
        c.setMediaItems(items, startIndex, startPositionMs)
        c.prepare()
        c.play()
        syncFromPlayer()
        persist()
    }

    /**
     * 秒开起播的第二阶段：把后台解析完的曲目追加到队尾。
     * 仅当当前播放曲仍是起播目标（mediaId 匹配）才追加，防止期间
     * 用户点了别的歌单导致错队列污染。
     */
    fun appendQueue(items: List<MediaItem>, expectedCurrentMediaId: String): Boolean {
        val c = controller ?: return false
        if (items.isEmpty()) return false
        if (c.currentMediaItem?.mediaId != expectedCurrentMediaId) return false
        Log.d("PlayerController", "appendQueue +${items.size} (now ${c.mediaItemCount + items.size})")
        c.addMediaItems(items)
        syncFromPlayer()
        persist()
        return true
    }

    /**
     * 把解析好的前置曲目插到队首（点播列表中间时补 [0, index)）。
     * Media3 插入后当前曲下标自动后移，播放不中断。
     */
    fun prependQueue(items: List<MediaItem>, expectedCurrentMediaId: String): Boolean {
        val c = controller ?: return false
        if (items.isEmpty()) return false
        if (c.currentMediaItem?.mediaId != expectedCurrentMediaId) return false
        Log.d("PlayerController", "prependQueue +${items.size} (now ${c.mediaItemCount + items.size})")
        c.addMediaItems(0, items)
        syncFromPlayer()
        persist()
        return true
    }

    fun togglePlayPause() {
        val c = controller ?: run {
            connect() // 断连重连窗口期内点按：补一次连接，下次点按即可用
            return
        }
        if (c.mediaItemCount == 0) return
        when {
            c.isPlaying -> c.pause()
            // 播完后 play() 无效，需回默认位置重新 prepare
            c.playbackState == Player.STATE_ENDED -> {
                c.seekToDefaultPosition()
                c.prepare()
                c.play()
            }
            // 播放出错后播放器停在 IDLE：play() 是空操作，必须重新解析流地址再 prepare，
            // 否则切后台久了/服务被杀后回来点播放键毫无反应
            c.playbackState == Player.STATE_IDLE -> recoverAndPlay(c)
            else -> c.play()
        }
        syncFromPlayer()
    }

    /**
     * IDLE 态恢复播放：在线流地址约 20 分钟过期，后台挂久或服务被杀后
     * 队列里存的都是死链，直接 prepare 只会再次 Source error。
     * 先重解析替换当前曲 URI，再从原进度 prepare + play。
     */
    private fun recoverAndPlay(c: MediaController) {
        val index = c.currentMediaItemIndex
        if (index !in 0 until c.mediaItemCount) {
            c.prepare()
            c.play()
            return
        }
        val item = c.getMediaItemAt(index)
        val position = c.currentPosition.coerceAtLeast(0)
        val song = if (item.mediaId.startsWith("local:")) null else item.toSongOrNull()
        if (song == null) {
            c.prepare() // 本地歌 URI 不过期，直接 prepare 即可
            c.play()
            return
        }
        Log.w("PlayerController", "IDLE 态点播放，重解析 ${item.mediaId}")
        scope.launch {
            val fresh = runCatching {
                song.toMediaItem(pluginManager, cachedMusicStore)
            }.getOrNull()
            val cc = controller ?: return@launch
            if (cc.currentMediaItemIndex != index) return@launch // 期间已切歌
            if (fresh != null) {
                cc.replaceMediaItem(index, fresh)
                cc.seekTo(index, position)
            }
            cc.prepare()
            cc.play()
            syncFromPlayer()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        // 超过 3 秒先回开头，否则跳上一首（标准播放器交互）
        if (c.currentPosition > 3000 || !c.hasPreviousMediaItem()) {
            c.seekTo(0)
        } else {
            c.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        syncFromPlayer()
        persist()
    }

    /** 跳到队列指定下标并播放（队列面板用）。 */
    fun seekToIndex(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekToDefaultPosition(index)
        c.play()
        syncFromPlayer()
        persist()
    }

    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
        syncFromPlayer()
        persist()
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        syncFromPlayer()
        persist()
    }

    /**
     * 实时播放位置（须在主线程调用，MediaController 约束）。
     * 悬浮歌词 tick 用：绕开 [ui] 500ms 轮询的陈旧度，扫色才平滑。
     */
    fun livePositionMs(): Long {
        val c = controller ?: return ui.value.positionMs
        return c.currentPosition.coerceAtLeast(0)
    }

    /** 设置播放倍速（立即生效并持久化）。 */
    fun setSpeed(speed: Float) {
        _speed.value = speed
        controller?.setPlaybackSpeed(speed)
        scope.launch {
            context.dataStore.edit { it[floatPreferencesKey(KEY_SPEED)] = speed }
        }
    }

    /** 启动睡眠定时：到时自动暂停。 */
    fun startSleepTimer(durationMs: Long) {
        val endAt = System.currentTimeMillis() + durationMs.coerceAtLeast(60_000)
        scope.launch {
            context.dataStore.edit { it[longPreferencesKey(KEY_SLEEP_END_AT)] = endAt }
        }
        scheduleSleep(endAt)
    }

    /** 取消睡眠定时。 */
    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        _sleepRemainingMs.value = 0
        scope.launch {
            context.dataStore.edit { it.remove(longPreferencesKey(KEY_SLEEP_END_AT)) }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 连接后恢复倍速与未到期的睡眠定时。 */
    private suspend fun applySavedPlaybackPrefs() {
        val prefs = context.dataStore.data
        val savedSpeed = prefs
            .mapNotNull { it[floatPreferencesKey(KEY_SPEED)] }
            .firstOrNull() ?: 1.0f
        _speed.value = savedSpeed
        controller?.setPlaybackSpeed(savedSpeed)
        val endAt = prefs
            .mapNotNull { it[longPreferencesKey(KEY_SLEEP_END_AT)] }
            .firstOrNull()
        if (endAt != null && endAt > System.currentTimeMillis()) {
            scheduleSleep(endAt)
        } else if (endAt != null) {
            context.dataStore.edit { it.remove(longPreferencesKey(KEY_SLEEP_END_AT)) }
        }
    }

    private fun scheduleSleep(endAt: Long) {
        sleepJob?.cancel()
        sleepJob = scope.launch {
            while (true) {
                val remain = endAt - System.currentTimeMillis()
                if (remain <= 0) {
                    controller?.pause()
                    _sleepRemainingMs.value = 0
                    context.dataStore.edit { it.remove(longPreferencesKey(KEY_SLEEP_END_AT)) }
                    break
                }
                _sleepRemainingMs.value = remain
                delay(1000)
            }
        }
    }

    private fun restoreIfNeeded() {
        val c = controller ?: return
        if (restored || c.mediaItemCount > 0) {
            restored = true
            return
        }
        restored = true
        scope.launch {
            val saved = stateStore.load() ?: return@launch
            val currentMediaId = saved.items.getOrNull(saved.index)?.mediaId
            val items = saved.items
                .filter { it.uri.isNotBlank() }
                .map { it.toMediaItem() }
                // 命中本地缓存的曲目改用缓存文件：流地址约 20 分钟过期，恢复旧队列直接 403
                .map { item ->
                    val local = cachedMusicStore.localUriFor(item.mediaId)
                        ?: return@map item
                    item.buildUpon()
                        .setUri(local)
                        .build()
                }
            if (items.isEmpty()) return@launch
            // 过滤空白 URI 会错位下标：按 mediaId 重新定位当前曲目
            val startIndex = items.indexOfFirst { it.mediaId == currentMediaId }
                .takeIf { it >= 0 } ?: 0
            c.setMediaItems(items, startIndex, saved.positionMs)
            c.prepare() // 预加载，恢复后立即有 duration/元数据（不自动播放）
            c.playWhenReady = false
            c.repeatMode = saved.repeatMode
            c.shuffleModeEnabled = saved.shuffle
            syncFromPlayer()
        }
    }

    /**
     * 流地址过期/网络抖动导致的播放错误：在线曲目自动重新解析一次并从原进度续播。
     * 同一曲目 30 秒内只重试一次，防止坏地址死循环。
     */
    private fun handleStreamError(error: androidx.media3.common.PlaybackException) {
        val retriable = error.errorCode in setOf(
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            // 流地址过期时源站常返回 JSON/HTML 错误页：extractor 认不出容器
            // → UnrecognizedInputFormatException → 3001/3003（parsing 段），不放行则永不重试
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        )
        if (!retriable) return
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index !in 0 until c.mediaItemCount) return
        val item = c.getMediaItemAt(index)
        val song = item.toSongOrNull() ?: return // 本地歌无从重解析
        val now = System.currentTimeMillis()
        if (item.mediaId == lastRetryMediaId && now - lastRetryAt < 30_000) return
        lastRetryMediaId = item.mediaId
        lastRetryAt = now
        val position = c.currentPosition.coerceAtLeast(0)
        val shouldPlay = c.playWhenReady
        Log.w("PlayerController", "播放失败(${error.errorCode})，重解析 ${item.mediaId}")
        scope.launch {
            val fresh = runCatching {
                song.toMediaItem(pluginManager, cachedMusicStore)
            }.getOrNull() ?: return@launch
            val cc = controller ?: return@launch
            if (cc.currentMediaItemIndex != index) return@launch // 已切歌，放弃
            cc.replaceMediaItem(index, fresh)
            cc.seekTo(index, position)
            cc.prepare()
            if (shouldPlay) cc.play()
        }
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        _ui.value = UiState(
            connected = true,
            queue = c.queueSnapshot(),
            current = c.currentMediaItem,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 }
                ?: c.currentMediaItem?.mediaMetadata?.durationMs?.takeIf { it > 0 } ?: 0,
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            buffering = c.playbackState == Player.STATE_BUFFERING
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                val c = controller ?: break
                if (c.isPlaying || c.playbackState == Player.STATE_BUFFERING) {
                    syncFromPlayer()
                    if (++tick % 6 == 0) persist() // 播放中每 3 秒落一次进度
                }
            }
        }
    }

    private fun persist() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return
        stateStore.save(
            PersistedQueue(
                items = c.queueSnapshot().map { it.toPersisted() },
                index = c.currentMediaItemIndex.coerceAtLeast(0),
                // 播完的进度存 0，下次从头开始而不是卡在结尾
                positionMs = if (c.playbackState == Player.STATE_ENDED) {
                    0
                } else {
                    c.currentPosition.coerceAtLeast(0)
                },
                repeatMode = c.repeatMode,
                shuffle = c.shuffleModeEnabled
            )
        )
    }

    private suspend fun recordRecent(item: MediaItem) {
        val mediaId = item.mediaId
        if (mediaId.startsWith("local:")) {
            runCatching {
                mediaId.removePrefix("local:").toLongOrNull()?.let {
                    recentPlayDao.recordPlay(RecentPlayEntity(songId = it))
                }
            }
        } else if (mediaId.contains(':')) {
            item.toSongOrNull()?.let { song ->
                runCatching { recentStore.record(song) }
            }
        }
    }
    companion object {
        private const val KEY_SPEED = "playback_speed"
        private const val KEY_SLEEP_END_AT = "sleep_end_at"

        /** 切歌稳定后才预取歌词的防抖时长（连按下一首时不入队 lyrics 调用）。 */
        private const val LYRICS_PREFETCH_DEBOUNCE_MS = 500L
    }
}

/** media3 1.11 起不再提供 mediaItems 列表属性，用 count+at 逐个取。 */
private fun MediaController.queueSnapshot(): List<MediaItem> =
    (0 until mediaItemCount).map { getMediaItemAt(it) }

/** 本地歌 → 可播放的 MediaItem。 */
fun LocalSongEntity.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(MediaStoreRepository.albumArtUri(albumId))
            .build()
    )
    .build()
