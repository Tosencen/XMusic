package com.xmgo.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * 网盘直链的懒解析（占位 URI → 播到哪首才换真链接）。
 *
 * 为什么不预解析：阿里直链 15 分钟过期，起播前把整份队列的直链都换好，
 * 一个 434 首的目录要打 434 次接口，而且绝大多数还没轮到播就已作废。
 * 改成懒解析后：点歌零等待（构造队列不用网络）、链接永远新鲜、队列长度不受限。
 *
 * 外层 [androidx.media3.datasource.cache.CacheDataSource] 仍按
 * MediaItem.customCacheKey（= mediaId）查磁盘缓存，命中时根本不会走到这里。
 */
object CloudStream {
    const val SCHEME = "cloud"

    /**
     * mediaId **不含冒号**：`PlayerController.recordRecent` 只认 `local:` 前缀和
     * `sourceId:rawId` 两种形态，网盘曲目暂不进最近播放，用无冒号 id 让它干净跳过，
     * 不会在最近播放里留下点了就报错的坏条目。
     */
    fun mediaIdOf(track: CloudTrack): String =
        "$SCHEME/${track.sourceType.name.lowercase()}/${track.fileId}"

    fun uriOf(track: CloudTrack): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(track.sourceType.name.lowercase())
        .appendPath(track.fileId)
        .build()

    /** 这个 mediaId 是不是网盘曲目（UI 拿它决定封面/来源展示等）。 */
    fun isCloudMediaId(mediaId: String?): Boolean = mediaId?.startsWith("$SCHEME/") == true

    /** 占位 URI → (音源, fileId)；非网盘 URI 返回 null。 */
    fun parse(uri: Uri): Pair<CloudSourceType, String>? {
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        val type = CloudSourceType.entries.firstOrNull {
            it.name.equals(uri.authority, ignoreCase = true)
        } ?: return null
        val fileId = uri.path?.trimStart('/').orEmpty()
        return if (fileId.isEmpty()) null else type to fileId
    }
}

/** 网盘曲目 → 可播放 MediaItem（URI 是占位符，真链路由 [CloudResolvingDataSource] 播放时换）。 */
fun CloudTrack.toMediaItem(): MediaItem {
    val mediaId = CloudStream.mediaIdOf(this)
    /*
     * 缓存键必须在这里（MediaItem.customCacheKey）就区分「转码流」与「原文件」。
     *
     * CacheDataSource 在解析层**外层**：命中磁盘缓存时根本不会往下走，
     * 所以在 CloudResolvingDataSource 里改 dataSpec.key 影响不到缓存查找。
     * 历史 bug：键里没有 flow 标记 → 开着流畅模式播之前缓存过的原文件（WMA），
     * 看起来像「流畅模式没生效」。
     *
     * 代价：切开关后同一首歌会重新缓存一份（LRU 会自动淘汰旧的）。
     */
    val cacheKey = mediaId + if (CloudSourceManager.flowMode.value) "/flow" else ""
    // 阿里 CDN 有防盗链：不带浏览器 UA/Referer/Origin 会返回 403（实测 OkHttp 默认 UA 被拒）。
    // Origin 也是常见校验项，不能漏。经 StreamHeaders → HeaderInjectingDataSource 按
    // dataSpec.key（= customCacheKey）注入，所以这里必须用 cacheKey 登记，不能用 mediaId。
    StreamHeaders.put(
        cacheKey,
        mapOf(
            "Origin" to "https://www.aliyundrive.com",
            "Referer" to "https://www.aliyundrive.com/",
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        )
    )
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(CloudStream.uriOf(this))
        .setCustomCacheKey(cacheKey)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name.substringBeforeLast('.'))
                .build()
        )
        .build()
}

/**
 * 把 `cloud://<sourceType>/<fileId>` 换成真直链后再交给下游。
 * [DataSpec.key]（= mediaId）经 [DataSpec.buildUpon] 保留，磁盘缓存不受换链影响。
 */
@UnstableApi
private class CloudResolvingDataSource(
    private val delegate: DataSource
) : BaseDataSource(/* isNetwork = */ true) {
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val parsed = CloudStream.parse(dataSpec.uri)
        var spec = dataSpec
        if (parsed != null) {
            val (type, fileId) = parsed
            // 「流畅模式」：优先用阿里服务端转码流（128kbps MP3，可流式、可 range 拖动、
            // 硬解省电、省流量约 6 成）。拿不到就回落 FFmpeg 直解原文件。
            val flow = if (CloudSourceManager.flowMode.value) {
                try {
                    runBlocking { CloudScanManager.getFlowUrl(type, fileId) }
                } catch (t: Throwable) {
                    null
                }
            } else {
                null
            }
            val url = flow ?: try {
                runBlocking { CloudScanManager.getTrackUrl(type, fileId).getOrThrow() }
            } catch (t: Throwable) {
                throw IOException("解析网盘直链失败: ${t.message}", t)
            }
            // 缓存键的 flow 区分在 toMediaItem() 里就做好了（见那里的注释）
            spec = dataSpec.buildUpon().setUri(url).build()
        }
        val totalBytes = delegate.open(spec)
        opened = true
        transferStarted(dataSpec)
        return totalBytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val read = delegate.read(buffer, offset, length)
        if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) {
            transferEnded()
            opened = false
            return androidx.media3.common.C.RESULT_END_OF_INPUT
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

/** [CloudResolvingDataSource] 的工厂。 */
@UnstableApi
class CloudResolvingDataSourceFactory(
    private val delegate: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = CloudResolvingDataSource(delegate.createDataSource())
}
