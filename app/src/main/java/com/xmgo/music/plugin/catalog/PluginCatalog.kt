package com.xmgo.music.plugin.catalog

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 内置插件目录条目（对齐 qplayer plugin-catalog.json 的仓库列表）。 */
data class CatalogSource(
    val id: String,
    val name: String,
    val description: String,
    /** GitHub `owner/repo` */
    val repo: String
)

/** 某仓库最新 release 中的 .qplug 下载信息。 */
data class CatalogRelease(
    val source: CatalogSource,
    val version: String,
    val downloadUrl: String,
    val releaseName: String = ""
)

/**
 * 插件在线目录：查 GitHub releases/latest（API 镜像回退），
 * 下载 .qplug（多镜像回退）。自用模式跳过签名校验（由 PluginManager.install 直接装）。
 */
class PluginCatalog(private val httpClient: OkHttpClient) {
    companion object {
        private const val TAG = "PluginCatalog"

        /** 与 qplayer plugin-catalog.json 同源（签名 key 不校验故省略）。 */
        val SOURCES = listOf(
            CatalogSource(
                "netease", "网易云音乐",
                "搜索、播放、歌词、登录、心动推荐与一起听",
                "TIMER-err/qplayer-netease-plugin"
            ),
            CatalogSource(
                "qq", "QQ 音乐",
                "搜索、播放、歌词与 Cookie 登录",
                "TIMER-err/qplayer-qqmusic-plugin"
            ),
            CatalogSource(
                "kuwo", "酷我音乐",
                "搜索、播放、歌词与 Cookie 登录",
                "MiaM1ku/qplayer-kuwo-plugin"
            ),
            CatalogSource(
                "migu", "咪咕音乐",
                "搜索、播放、歌词与 Cookie 登录",
                "MiaM1ku/qplayer-migu-plugin"
            ),
            CatalogSource(
                "kugou", "酷狗音乐",
                "搜索、播放、歌词、扫码登录与用户歌单",
                "MiaM1ku/qplayer-kugou-plugin"
            ),
            CatalogSource(
                "soda", "汽水音乐",
                "搜索、播放、歌词、扫码登录与用户歌单",
                "MiaM1ku/qplayer-soda-plugin"
            )
        )

        private const val API_MIRROR = "https://gh-proxy.com/"
        private val ASSET_MIRRORS = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://github.cnxiaobai.com/",
            "https://gh.ddlc.top/"
        )
        private const val MAX_PLUGIN_BYTES = 64L * 1024 * 1024
    }

    /** 并发查各仓库 latest release；单个失败跳过（一个仓库挂了不拖垮全部）。 */
    fun loadLatest(): List<CatalogRelease> = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.coroutineScope {
            SOURCES
                .map { source -> async(kotlinx.coroutines.Dispatchers.IO) { query(source) } }
                .mapNotNull { it.await() }
        }
    }

    private fun query(source: CatalogSource): CatalogRelease? {
        try {
            val api = "https://api.github.com/repos/${source.repo}/releases/latest"
            val body = getFirst(listOf(API_MIRROR + api, api))
            val root = JSONObject(body)
            val version = root.optString("tag_name").removePrefix("v").ifBlank {
                root.optString("name").removePrefix("v")
            }
            val assets = root.optJSONArray("assets") ?: return null
            var download: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".qplug")) {
                    download = asset.optString("browser_download_url")
                    break
                }
            }
            if (download.isNullOrBlank()) {
                Log.w(TAG, "no .qplug asset in ${source.repo}")
                return null
            }
            return CatalogRelease(source, version.ifBlank { "unknown" }, download, root.optString("name"))
        } catch (e: Exception) {
            Log.w(TAG, "release lookup failed for ${source.repo}: ${e.message}")
            return null
        }
    }

    /** 下载 .qplug（镜像逐个回退），成功写出 dest。 */
    fun download(release: CatalogRelease, dest: File) {
        val candidates = ASSET_MIRRORS.map { it + release.downloadUrl } + release.downloadUrl
        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val client = httpClient.newBuilder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    var written = 0L
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = body.byteStream().read(buf)
                            if (n < 0) break
                            written += n
                            if (written > MAX_PLUGIN_BYTES) throw IOException("plugin too large")
                            out.write(buf, 0, n)
                        }
                        if (written < 64) throw IOException("download too small ($written bytes)")
                    }
                    val head = ByteArray(2)
                    dest.inputStream().use { it.read(head) }
                    if (head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
                        throw IOException("not a zip (mirror returned HTML/redirect)")
                    }
                    Log.i(TAG, "downloaded ${release.source.id} v${release.version} ($written bytes)")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "download failed via $url: ${e.message}")
                lastError = e
                dest.delete()
            }
        }
        throw lastError ?: IOException("download failed: ${release.downloadUrl}")
    }

    /** 依次尝试候选 URL，返回首个 200 的 body。 */
    private fun getFirst(urls: List<String>): String {
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val client = httpClient.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                client.newCall(
                    Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    return resp.body?.string() ?: throw IOException("empty body")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GET $url failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IOException("all endpoints failed")
    }
}
