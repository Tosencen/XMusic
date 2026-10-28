package com.xmgo.music.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.xmgo.music.utils.appVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 检测到的应用新版本（tag → 版本号 + 更新日志 + APK 下载地址）。 */
data class UpdateInfo(
    /** 如 "1.2.0"（tag 去 v 前缀） */
    val version: String,
    /** GitHub release body（更新日志，可能为空） */
    val notes: String,
    /** 原始 browser_download_url（下载时再拼镜像前缀） */
    val apkUrl: String,
    /** 资产字节数（进度分母，可为 0） */
    val sizeBytes: Long
)

/**
 * 应用自更新：查 GitHub releases/latest（gh-proxy 镜像回退，模式同 PluginCatalog），
 * 版本号与本机 versionName 分段数值比较；多镜像下载 APK 至 cacheDir/update/，
 * 经 FileProvider 调起系统安装页（O+ 由系统引导允许未知来源）。
 * 检出新版本经 [offer] 写入 [pendingUpdate]，全局弹窗宿主 collect 后消费。
 * 检查失败一律静默返回 null——自更新提示不打断使用。
 */
class AppUpdater(private val context: Context) {

    companion object {
        private const val TAG = "AppUpdater"
        private const val REPO = "Tosencen/XMusic"
        private const val API_MIRROR = "https://gh-proxy.com/"
        private val ASSET_MIRRORS = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://github.cnxiaobai.com/",
            "https://gh.ddlc.top/"
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)
    val pendingUpdate: StateFlow<UpdateInfo?> = _pendingUpdate.asStateFlow()

    /** 交给全局弹窗展示（启动检查与 About 手动检查共用入口）。 */
    fun offer(info: UpdateInfo) {
        _pendingUpdate.value = info
    }

    /** 消费：弹窗关闭/安装调起后清除，避免重复弹。 */
    fun consume() {
        _pendingUpdate.value = null
    }

    /** 查最新版：有新版返回信息；无新版返回 null；网络/解析异常向上抛（调用方决定提示）。 */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val api = "https://api.github.com/repos/$REPO/releases/latest"
        val body = getFirst(listOf(API_MIRROR + api, api))
        val root = JSONObject(body)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return@withContext null
        val latest = root.optString("tag_name").removePrefix("v")
            .ifBlank { root.optString("name").removePrefix("v") }
        if (!isNewer(latest, context.appVersion)) return@withContext null
        val assets = root.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url")
                size = asset.optLong("size", 0L)
                break
            }
        }
        if (apkUrl.isNullOrBlank()) {
            Log.w(TAG, "latest release 无 .apk 资产")
            return@withContext null
        }
        UpdateInfo(
            version = latest,
            notes = root.optString("body"),
            apkUrl = apkUrl,
            sizeBytes = size
        )
    }

    /** 版本比较：逐段数值比（"1.2.0" > "1.1.0"），段缺失按 0。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: 0
            val y = b.getOrNull(i) ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 下载 APK：同名已存在且字节数与 release 资产一致才复用；否则四镜像逐个回退。
     * 下载完成校验字节数（防截断/镜像返回错误页），不符则删除换下一镜像。
     * onProgress(received, total)，total 可为 -1（未知）。失败抛异常。
     */
    suspend fun download(info: UpdateInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            val target = File(dir, "XMusic-${info.version}.apk")
            // 完整性判断：有资产大小时必须严格相等；无大小（0）才退化为 >1KB
            if (target.isFile && target.length() > 1024 &&
                (info.sizeBytes <= 0 || target.length() == info.sizeBytes)
            ) {
                onProgress(target.length(), target.length())
                return@withContext target
            }
            val candidates = ASSET_MIRRORS.map { it + info.apkUrl } + info.apkUrl
            var lastError: Exception? = null
            for (url in candidates) {
                val tmp = File(dir, "XMusic-${info.version}.apk.part")
                try {
                    httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("空响应")
                        val total = body.contentLength().let { if (it > 0) it else info.sizeBytes }
                        tmp.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var received = 0L
                                var lastEmit = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    received += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= 150) {
                                        lastEmit = now
                                        onProgress(received, total)
                                    }
                                }
                                onProgress(received, total.coerceAtLeast(0))
                                // 字节数校验：防截断下载/镜像 200 + 错误页被当 APK 缓存安装
                                if (total > 0 && received != total) {
                                    error("下载不完整：$received/$total 字节")
                                }
                            }
                        }
                        if (!tmp.renameTo(target)) {
                            target.delete()
                            tmp.copyTo(target, overwrite = true)
                            tmp.delete()
                        }
                    }
                    return@withContext target
                } catch (e: Exception) {
                    lastError = e
                    tmp.delete()
                    Log.w(TAG, "镜像下载失败（$url）：${e.message}")
                }
            }
            throw lastError ?: IllegalStateException("下载失败")
        }

    /** 调起系统安装页（cache-path 已覆盖整个 cacheDir，无需改 file_paths）。未授权时先引导授权。 */
    fun install(file: File) {
        // 未授予"安装未知应用"权限时先跳转授权页，避免直接 startActivity 落到设置页后还要返回重进
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getFirst(urls: List<String>): String {
        var last: Exception? = null
        for (url in urls) {
            try {
                httpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("空响应")
                    return body.string()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }
}
