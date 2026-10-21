package com.xmgo.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 在线歌 → 可播放 MediaItem（已缓存直接用本地文件，否则 resolveStream 取流地址）。
 * mediaId 约定为 sourceId:rawId，与歌词查询保持一致。
 */
suspend fun Song.toMediaItem(
    pluginManager: PluginManager,
    cache: CachedMusicStore? = null
): MediaItem {
    val mediaId = "$sourceId:$rawId"
    // 命中本地缓存：免解析、免联网，且不受流地址过期影响
    val uri: String = cache?.localUriFor(mediaId)?.toString()
        ?: pluginManager.providerFor(sourceId).resolveStream(this).url
    // 注：media3 1.11 不支持 per-item HTTP headers，个别需要 header 的源暂不支持
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(artists.joinToString(" / ").ifEmpty { null })
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.let(Uri::parse))
                // setDurationMs 只接受 null 或 >=0，TIME_UNSET(负数) 会抛 IllegalArgumentException
            .setDurationMs(if (durationMs > 0) durationMs else null)
                .build()
        )
        .build()
}

/**
 * 批量解析（并行，带并发上限）：每首 resolveStream 会产生 1-2 个 HTTP 请求，
 * 全并发会塞满 OkHttp per-host 队列（maxRequestsPerHost=16），导致用户随后
 * 点击的另一首歌排队很久才起播。限并发后剩余槽位留给新点击。
 * 失败的曲目跳过。
 * 返回可直接交给 [PlayerController.playQueue] 的列表。
 */
private const val RESOLVE_CONCURRENCY = 4

suspend fun resolveQueue(
    songs: List<Song>,
    pluginManager: PluginManager,
    cache: CachedMusicStore? = null
): List<MediaItem> = withContext(Dispatchers.IO) {
    val semaphore = Semaphore(RESOLVE_CONCURRENCY)
    coroutineScope {
        songs
            .map { song ->
                async {
                    semaphore.withPermit {
                        runCatching { song.toMediaItem(pluginManager, cache) }.getOrNull()
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}

/** 起播后先补的「下一首窗口」曲数（保证马上按下一曲有歌）。 */
private const val NEXT_WINDOW = 5

/**
 * 后台补队列用的独立作用域：UI 协程取消（用户离开页面）也不能中断
 * 前置/后续曲目的解析插入，否则队列永远残缺。
 * 注意必须跑在 Main（MediaController 只能在应用主线程调用），
 * 重活（resolve/JS/HTTP）在 [resolveQueue] 内部已切 IO。
 */
private val stagedScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** 当前补队列任务：点新歌时取消，避免旧补队列的 HTTP 请求继续占用 OkHttp 队列。 */
private var stagedJob: Job? = null

/** 当前补队列的 toast 静默令牌：取消旧补队列时同步释放，防止吞掉新点击目标曲的提示。 */
private var stagedQuietToken: Any? = null

/** 取消在途补队列并结束其 toast 静默（幂等，可在任何顺序下安全调用）。 */
private fun cancelStagedFill() {
    stagedJob?.cancel()
    stagedQuietToken?.let {
        com.xmgo.music.plugin.runtime.PluginRuntime.endQuietToasts(it)
        stagedQuietToken = null
    }
}

/**
 * 秒开起播（对齐 qplayer "只解析当前曲" 思路）：
 * 1. 只解析点击的那一首 → [PlayerController.playQueue] 立即起播；
 * 2. [onStarted]（UI 此刻关"解析播放地址…"）；
 * 3. 独立协程后台补队列：先补 [NEXT_WINDOW] 首「下一首」→ 再补前置 [0, index)
 *    → 最后补剩余，全部通过 mediaId 校验防串台。
 *
 * 返回 false = 目标曲解析失败。
 */
suspend fun playSongsStaged(
    songs: List<Song>,
    index: Int,
    pluginManager: PluginManager,
    playerController: PlayerController,
    cache: CachedMusicStore? = null,
    onStarted: () -> Unit
): Boolean {
    val song = songs.getOrNull(index) ?: return false
    // 先取消旧补队列：令其在途 resolveStream 的 future 立即取消，
    // 释放单线程 actor 与 OkHttp 槽位给本次点击的起播解析
    cancelStagedFill()
    val t0 = System.currentTimeMillis()
    val target = withContext(Dispatchers.IO) {
        runCatching { song.toMediaItem(pluginManager, cache) }.getOrNull()
    } ?: return false
    android.util.Log.d("OnlinePlayback", "目标曲解析耗时 ${System.currentTimeMillis() - t0}ms ${target.mediaId}")
    playerController.playQueue(listOf(target), startIndex = 0)
    onStarted()
    stagedJob = stagedScope.launch {
        // 后台补队列期间静默插件 toast：批量 unblock 换源提示会每 30s 连发
        val quiet = com.xmgo.music.plugin.runtime.PluginRuntime.beginQuietToasts()
        stagedQuietToken = quiet
        try {
            fillQueueAround(
                mediaIds = songs.map { "${it.sourceId}:${it.rawId}" },
                resolve = { from, to ->
                    resolveQueue(songs.subList(from, to), pluginManager, cache)
                },
                index = index,
                targetId = target.mediaId,
                playerController = playerController
            )
        } finally {
            com.xmgo.music.plugin.runtime.PluginRuntime.endQuietToasts(quiet)
            if (stagedQuietToken === quiet) stagedQuietToken = null
        }
    }
    return true
}

/**
 * 起播后补全队列：先「下一首」窗口（用户马上按下一曲的需求）、再前置、再剩余。
 * 每一步都经 [PlayerController] 的 mediaId 校验，期间换歌则静默放弃。
 */
private suspend fun fillQueueAround(
    mediaIds: List<String>,
    resolve: suspend (from: Int, to: Int) -> List<MediaItem>,
    index: Int,
    targetId: String,
    playerController: PlayerController
) {
    val size = mediaIds.size
    // 1) 下一首窗口：index+1 .. min(index+NEXT_WINDOW, size)
    val nearFrom = index + 1
    val nearTo = minOf(nearFrom + NEXT_WINDOW, size)
    if (nearFrom < nearTo) {
        val near = resolve(nearFrom, nearTo)
        if (near.isNotEmpty()) playerController.appendQueue(near, targetId)
    }
    // 2) 前置：0 .. index（倒序解析保持相对顺序成本高，直接整段一次解析）
    if (index > 0) {
        val prefix = resolve(0, index)
        if (prefix.isNotEmpty()) playerController.prependQueue(prefix, targetId)
    }
    // 3) 剩余：nearTo .. size
    if (nearTo < size) {
        val rest = resolve(nearTo, size)
        if (rest.isNotEmpty()) playerController.appendQueue(rest, targetId)
    }
}

/** 混合播放条目：本地（直接构项）或在线（resolveStream 取流）。 */
sealed interface PlayableEntry {
    val mediaId: String

    data class Local(val song: com.xmgo.music.data.local.entity.LocalSongEntity) : PlayableEntry {
        override val mediaId: String get() = song.mediaId
    }

    data class Online(val song: Song) : PlayableEntry {
        override val mediaId: String get() = FavoritesStore.mediaIdOf(song)
    }
}

private suspend fun resolveEntries(
    entries: List<PlayableEntry>,
    pluginManager: PluginManager,
    cache: CachedMusicStore? = null
): List<MediaItem> = withContext(Dispatchers.IO) {
    val semaphore = Semaphore(RESOLVE_CONCURRENCY)
    coroutineScope {
        entries
            .map { entry ->
                async {
                    semaphore.withPermit {
                        when (entry) {
                            is PlayableEntry.Local -> entry.song.toMediaItem()
                            is PlayableEntry.Online ->
                                runCatching { entry.song.toMediaItem(pluginManager, cache) }.getOrNull()
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }
}

/**
 * 本地/在线混合列表的秒开起播（最近播放页用）：
 * 与 [playSongsStaged] 同策略 —— 只解析目标起播，其余后台补。
 * 返回 false = 目标解析失败。
 */
suspend fun playEntriesStaged(
    entries: List<PlayableEntry>,
    index: Int,
    pluginManager: PluginManager,
    playerController: PlayerController,
    cache: CachedMusicStore? = null,
    onStarted: () -> Unit
): Boolean {
    val entry = entries.getOrNull(index) ?: return false
    // 先取消旧补队列：释放单线程 actor 与 OkHttp 槽位给本次点击
    cancelStagedFill()
    val t0 = System.currentTimeMillis()
    val target = withContext(Dispatchers.IO) {
        when (entry) {
            is PlayableEntry.Local -> entry.song.toMediaItem()
            is PlayableEntry.Online ->
                runCatching { entry.song.toMediaItem(pluginManager, cache) }.getOrNull()
        }
    } ?: return false
    android.util.Log.d("OnlinePlayback", "目标曲解析耗时 ${System.currentTimeMillis() - t0}ms ${target.mediaId}")
    playerController.playQueue(listOf(target), startIndex = 0)
    onStarted()
    stagedJob = stagedScope.launch {
        val quiet = com.xmgo.music.plugin.runtime.PluginRuntime.beginQuietToasts()
        stagedQuietToken = quiet
        try {
            fillQueueAround(
                mediaIds = entries.map { it.mediaId },
                resolve = { from, to ->
                    resolveEntries(entries.subList(from, to), pluginManager, cache)
                },
                index = index,
                targetId = target.mediaId,
                playerController = playerController
            )
        } finally {
            com.xmgo.music.plugin.runtime.PluginRuntime.endQuietToasts(quiet)
            if (stagedQuietToken === quiet) stagedQuietToken = null
        }
    }
    return true
}
