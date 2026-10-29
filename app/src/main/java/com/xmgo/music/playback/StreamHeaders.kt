package com.xmgo.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * mediaId → 取流所需 HTTP 头（防盗链源用，如 B 站的 Referer + UA）。
 *
 * 为什么需要这层注册表：media3 的 [androidx.media3.common.MediaItem] 无法携带
 * per-item HTTP 头（1.11 仍不支持），插件 [com.xmgo.music.plugin.model.StreamInfo.headers]
 * 只能先登记在这里，再由 [HeaderInjectingDataSource] 在真正发起请求时按
 * [DataSpec.key]（即 MediaItem.customCacheKey = mediaId）取出并注入请求头。
 *
 * 只存活于进程内：流地址本身 15 分钟过期，换一首歌会重新 resolveStream 并覆盖登记。
 */
object StreamHeaders {
    /** LRU 上限：歌单补队列可能登记几百首，超出后淘汰最久未用的。 */
    private const val MAX_ENTRIES = 512

    private val map = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>) =
            size > MAX_ENTRIES
    }

    private val lock = Any()

    fun put(mediaId: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        synchronized(lock) { map[mediaId] = HashMap(headers) }
    }

    fun get(mediaId: String): Map<String, String>? = synchronized(lock) { map[mediaId] }

    fun remove(mediaId: String) {
        synchronized(lock) { map.remove(mediaId) }
    }
}

/**
 * 按 [DataSpec.key] 注入 [StreamHeaders] 中登记的请求头的数据源包装。
 *
 * 本地缓存命中时 [androidx.media3.datasource.cache.CacheDataSource] 不会调用上游，
 * 因此不会误注入；未设 customCacheKey 的本地文件 [DataSpec.key] 为 null，同样跳过。
 */
@UnstableApi
private class HeaderInjectingDataSource(delegate: DataSource) : BaseDataSource(/* isNetwork = */ true) {
    private val delegate = delegate
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val headers = dataSpec.key?.let { StreamHeaders.get(it) }
        val spec = if (headers.isNullOrEmpty()) {
            dataSpec
        } else {
            dataSpec.buildUpon()
                .setHttpRequestHeaders(dataSpec.httpRequestHeaders + headers)
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

/** [HeaderInjectingDataSource] 的工厂，包在 [androidx.media3.datasource.DefaultDataSource] 外层。 */
@UnstableApi
class HeaderInjectingDataSourceFactory(
    private val delegate: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = HeaderInjectingDataSource(delegate.createDataSource())
}
