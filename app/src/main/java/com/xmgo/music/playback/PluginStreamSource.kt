package com.xmgo.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * 插件曲目的懒解析（占位 URI → 播到哪首才换真链接），对齐 [CloudStream] 的做法。
 *
 * **为什么必须懒解析**：`resolveStream` 是一次 Rhino JS + HTTP 请求。
 * 原先在 `Song.toMediaItem()` 里就解析，100 首的歌单要 100 次网络请求才能把队列建起来，
 * 表现为「播放队列慢慢长出来」。而队列 UI 只需要**元数据**（标题/歌手/时长），
 * 流地址是真正播到那首时才用得上。
 *
 * 改成懒解析后：队列瞬时完整、点歌零等待（省掉「解析完才 playQueue」的等待）、
 * 链接永远新鲜。这与 QPlayer 的做法一致（其 PlayerController 注释明确写
 * "Source URLs are resolved **lazily** for the selected request"）。
 *
 * 外层 [androidx.media3.datasource.cache.CacheDataSource] 仍按
 * MediaItem.customCacheKey（= mediaId）查磁盘缓存，命中时根本不会走到这里。
 */
object PluginStream {
    const val SCHEME = "plugin"

    /** mediaId 沿用 `sourceId:rawId`，与收藏/最近播放/缓存登记保持一致。 */
    fun mediaIdOf(song: Song): String = "${song.sourceId}:${song.rawId}"

    /** 占位 URI：`plugin://<sourceId>/<rawId>`。 */
    fun uriOf(song: Song): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(song.sourceId)
        .appendPath(song.rawId)
        .build()

    fun isPluginUri(uri: Uri?): Boolean = uri != null && SCHEME.equals(uri.scheme, true)

    /** 占位 URI → (sourceId, rawId)；非插件占位返回 null。 */
    fun parse(uri: Uri): Pair<String, String>? {
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val sourceId = uri.authority?.takeIf { it.isNotEmpty() } ?: return null
        val rawId = uri.path?.trimStart('/').orEmpty()
        return if (rawId.isEmpty()) null else sourceId to rawId
    }
}

/**
 * mediaId → [Song] 登记表：懒解析时按 mediaId 反查插件 `resolveStream` 需要的 Song。
 *
 * 进程内 LRU。队列构建时登记；杀进程重启后由 [SongStore.restoreFrom] 从
 * [PersistedItem] 逐条重建（mediaId 拆 sourceId:rawId，其余字段来自持久化的元数据）。
 */
object SongStore {
    private const val MAX_ENTRIES = 512

    private val map = object : LinkedHashMap<String, Song>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) =
            size > MAX_ENTRIES
    }
    private val lock = Any()

    fun put(song: Song) {
        synchronized(lock) { map[mediaIdOf(song)] = song }
    }

    fun putAll(songs: Iterable<Song>) {
        synchronized(lock) { songs.forEach { map[mediaIdOf(it)] = it } }
    }

    fun get(mediaId: String): Song? = synchronized(lock) { map[mediaId] }

    /** 从单条持久化条目重建并登记（队列恢复逐条调用）。 */
    fun restoreItem(item: PersistedItem) {
        rebuild(item)?.let { put(it) }
    }

    private fun rebuild(p: PersistedItem): Song? {
        val sep = p.mediaId.indexOf(':')
        if (sep <= 0 || sep == p.mediaId.length - 1) return null
        return Song(
            id = p.mediaId,
            name = p.title,
            artists = if (p.artist.isBlank()) emptyList() else listOf(p.artist),
            album = p.album,
            durationMs = p.durationMs,
            artworkUrl = p.artworkUri,
            sourceId = p.mediaId.substring(0, sep),
            rawId = p.mediaId.substring(sep + 1)
        )
    }

    private fun mediaIdOf(song: Song) = PluginStream.mediaIdOf(song)
}

/**
 * 把 `plugin://<sourceId>/<rawId>` 换成真流地址后再交给下游。
 *
 * [DataSpec.key]（= mediaId）经 [DataSpec.buildUpon] 保留，磁盘缓存不受换链影响；
 * 防盗链请求头登记进 [StreamHeaders]，由 [HeaderInjectingDataSource] 注入。
 */
@UnstableApi
private class PluginResolvingDataSource(
    private val delegate: DataSource,
    private val pluginManager: PluginManager
) : BaseDataSource(/* isNetwork = */ true) {
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val parsed = PluginStream.parse(dataSpec.uri)
        var spec = dataSpec
        if (parsed != null) {
            val (sourceId, rawId) = parsed
            val mediaId = "$sourceId:$rawId"
            val song = SongStore.get(mediaId)
                ?: throw IOException("插件曲目未登记，无法解析流地址: $mediaId")
            val stream = try {
                // 与 CloudResolvingDataSource 一致：open() 是阻塞点，
                // 插件 resolveStream 是 suspend，这里用 runBlocking 等结果
                runBlocking { pluginManager.providerFor(sourceId).resolveStream(song) }
            } catch (t: Throwable) {
                throw IOException("解析插件流地址失败: ${t.message}", t)
            }
            if (stream.url.isEmpty()) throw IOException("解析插件流地址失败: 空 URL")
            // 防盗链头（B 站要 Referer/UA）**直接写进 spec**，不走 StreamHeaders：
            // HeaderInjectingDataSource 在**解析之前**读 StreamHeaders，
            // 而懒解析是这一刻才拿到 headers，登记来不及被它看到。
            // 用 buildUpon 合并而非替换，保住上游已注入的头
            spec = dataSpec.buildUpon()
                .setUri(stream.url)
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + stream.headers)
                .build()
        }
        val totalBytes = delegate.open(spec)
        opened = true
        transferStarted(dataSpec)
        return totalBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = delegate.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            transferEnded()
            opened = false
            return C.RESULT_END_OF_INPUT
        }
        bytesTransferred(read)
        return read
    }

    override fun close() {
        if (opened) {
            transferEnded()
            opened = false
        }
        delegate.close()
    }

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun getUri(): Uri? = delegate.uri
}

/** [PluginResolvingDataSource] 的工厂。 */
@UnstableApi
class PluginResolvingDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val pluginManager: PluginManager
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        PluginResolvingDataSource(delegate.createDataSource(), pluginManager)
}
