package com.xmgo.music.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 阿里云盘轻量客户端（P0：登录验证；直链/扫描 P2 接入）。
 * refresh_token → access_token（社区通用接口），token 由 CloudSourceManager 管。
 */
object AliDriveApi {
    private const val TAG = "AliDrive"
    private const val TOKEN_URL = "https://auth.alipan.com/v2/account/token"

    /**
     * 共享一个 OkHttpClient：每个请求 new 一个会各自开连接池和线程池，
     * 列目录一次翻 5 页就是 5 套线程，纯浪费还拖慢。
     */
    private val http = okhttp3.OkHttpClient()

    /**
     * 换 access_token 的结果。注意：这里**不带** drive 信息——
     * 阿里是多盘模型，`default_drive_id` 是备份盘（常为空），
     * 用户文件在资源库盘，必须用 [listMyDrives] 单独枚举。
     */
    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val nickname: String?,
        val userId: String?
    )

    /** 网盘文件条目（列目录）。 */
    data class CloudFile(
        val fileId: String,
        val name: String,
        val type: String, // file / folder
        val size: Long = 0
    )

    /** 列目录（adrive/v3/file/list）。自动翻页——单页上限 limit，不限页会漏文件。 */
    suspend fun listFiles(
        accessToken: String,
        driveId: String,
        parentFileId: String = "root",
        limit: Int = 100
    ): Result<List<CloudFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val out = mutableListOf<CloudFile>()
            var marker = ""
            var pages = 0
            do {
                val body = JSONObject()
                    .put("drive_id", driveId)
                    .put("parent_file_id", parentFileId)
                    .put("limit", limit)
                    .put("all", false)
                    .put("fields", "*")
                    // 显式排序：不带 order_by 时接口按 size 降序返回，专辑听着是乱序。
                    // 按文件名升序 → "01.xx" "02.xx" 正好是曲序（order_direction 必须大写）。
                    .put("order_by", "name")
                    .put("order_direction", "ASC")
                if (marker.isNotEmpty()) body.put("marker", marker)
                val req = okhttp3.Request.Builder()
                    .url("https://api.aliyundrive.com/adrive/v3/file/list")
                    .header("Authorization", "Bearer $accessToken")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (pages == 0) Log.d(TAG, "listFiles raw: ${text.take(300)}")
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                    val json = JSONObject(text)
                    val files = json.getJSONArray("items")
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        out += CloudFile(
                            fileId = f.getString("file_id"),
                            name = f.getString("name"),
                            type = f.optString("type", "file"),
                            size = f.optLong("size", 0)
                        )
                    }
                    marker = json.optString("next_marker")
                    pages++
                }
            } while (marker.isNotEmpty() && pages < 50) // 上限 5000 条，防死循环
            Log.i(TAG, "listFiles[$parentFileId]: ${out.size} items in $pages page(s)")
            out
        }.onFailure { Log.w(TAG, "listFiles failed: $it") }
    }

    /** 取下载直链（v2/file/get_download_url，限时有效——播放时实时取）。 */
    suspend fun getDownloadUrl(
        accessToken: String,
        driveId: String,
        fileId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
                val body = JSONObject()
                    .put("drive_id", driveId)
                    .put("file_id", fileId)
                    .put("expire_sec", 14400) // 4 小时（网页版用的值）；直链仍是播放时实时取
                .toString()
            val req = okhttp3.Request.Builder()
                .url("https://api.aliyundrive.com/v2/file/get_download_url")
                .header("Authorization", "Bearer $accessToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                val json = JSONObject(text)
                // 优先 cdn_url（CDN 域名通常比 data 主站快），没有再退到 url
                val url = json.optString("cdn_url").takeIf { it.isNotBlank() }
                    ?: json.getString("url")
                Log.i(
                    TAG,
                    "getDownloadUrl: cdn=${json.optString("cdn_url").isNotBlank()} " +
                        "过期=${OssExpiry.remainingMs(url).let { if (it == Long.MAX_VALUE) "未知" else "${it / 1000}s" }}"
                )
                url
            }
        }.onFailure { Log.w(TAG, "getDownloadUrl failed: $it") }
    }

    /** 服务端转码的结果。 */
    sealed class AudioPlayInfo {
        /** 有可直接播的转码流（URL 已含签名，标准 HTTP 流，支持 range）。 */
        data class Ready(val url: String, val templateId: String) : AudioPlayInfo()

        /** 还在转码中，稍后重试。 */
        object Transcoding : AudioPlayInfo()

        /** 没有转码信息（该文件/格式不支持服务端转码）。 */
        object Unavailable : AudioPlayInfo()
    }

    /**
     * 取音频**服务端转码**流（阿里把音频转成可直接播的 MP3）。
     *
     * `template_list` 里是多档模板（`HQ`/`LQ`…，各带 `status` 与 `url`）：
     * 优先取 `HQ`，其次 `LQ`，最后任意 finished 的；一个都没有时看是否有 running 的
     * 判定为「转码中」。响应码 `AudioPreviewWaitAndRetry` 同样表示转码中。
     *
     * 实测（2026-10-30，某 WMA 文件）：只有 `LQ` 档、128kbps MP3、源 321kbps WMA，
     * 故只能当「省流量/流畅模式」用，不能做音质默认。
     */
    suspend fun getAudioPlayInfo(
        accessToken: String,
        driveId: String,
        fileId: String
    ): Result<AudioPlayInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("drive_id", driveId)
                .put("file_id", fileId)
                .put("url_expire_sec", 14400)
                .toString()
            val req = okhttp3.Request.Builder()
                .url("https://api.aliyundrive.com/v2/file/get_audio_play_info")
                .header("Authorization", "Bearer $accessToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    Log.w(TAG, "getAudioPlayInfo: 响应不是 JSON (HTTP ${resp.code})")
                    return@use AudioPlayInfo.Unavailable
                }
                if (json.optString("code") == "AudioPreviewWaitAndRetry") {
                    return@use AudioPlayInfo.Transcoding
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "getAudioPlayInfo: HTTP ${resp.code} ${text.take(160)}")
                    return@use AudioPlayInfo.Unavailable
                }
                val list = json.optJSONArray("template_list")
                if (list == null || list.length() == 0) return@use AudioPlayInfo.Unavailable

                var hq: AudioPlayInfo.Ready? = null
                var lq: AudioPlayInfo.Ready? = null
                var any: AudioPlayInfo.Ready? = null
                var hasRunning = false
                for (i in 0 until list.length()) {
                    val t = list.optJSONObject(i) ?: continue
                    val id = t.optString("template_id")
                    val status = t.optString("status")
                    val url = t.optString("url")
                    if (status == "running") hasRunning = true
                    if (status != "finished" || url.isBlank()) continue
                    val ready = AudioPlayInfo.Ready(url, id)
                    any = any ?: ready
                    when (id) {
                        "HQ" -> hq = hq ?: ready
                        "LQ" -> lq = lq ?: ready
                    }
                }
                val picked = hq ?: lq ?: any
                when {
                    picked != null -> picked
                    hasRunning -> AudioPlayInfo.Transcoding
                    else -> AudioPlayInfo.Unavailable
                }
            }
        }.onFailure { Log.w(TAG, "getAudioPlayInfo failed: $it") }
    }

    /** 一个「盘」。阿里账号下有多个盘（备份盘/资源库/相册/笔记/保险箱…），各自独立列目录。 */
    data class DriveTarget(
        val driveId: String,
        val rootFolderId: String,
        val kind: String,      // resource / backup / album / note / sbox / sharedAlbum
        val label: String,
        val usedSize: Long
    )

    /**
     * 枚举账号下所有盘（`/v2/drive/list_my_drives`——`v2/user/get` 只给 default_drive_id，
     * 而那是个空的备份盘，用来列目录必然是空的）。
     */
    suspend fun listMyDrives(accessToken: String): Result<List<DriveTarget>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url("https://api.aliyundrive.com/v2/drive/list_my_drives")
                    .header("Authorization", "Bearer $accessToken")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Log.i(TAG, "listMyDrives raw: ${text.take(1200)}")
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                    val items = JSONObject(text).getJSONArray("items")
                    buildList {
                        for (i in 0 until items.length()) {
                            val d = items.getJSONObject(i)
                            val kind = d.optString("category").ifBlank { d.optString("drive_name") }
                            add(
                                DriveTarget(
                                    driveId = d.getString("drive_id"),
                                    rootFolderId = "root",
                                    kind = kind,
                                    label = labelOf(kind, d.optString("drive_name")),
                                    usedSize = d.optLong("used_size", 0)
                                )
                            )
                        }
                    }.also { list ->
                        Log.i(TAG, "listMyDrives: " + list.joinToString { "${it.label}=${it.driveId}(${it.usedSize}B)" })
                    }
                }
            }.onFailure { Log.w(TAG, "listMyDrives failed: $it") }
        }

    private fun labelOf(kind: String, driveName: String): String = when {
        kind == "resource" -> "资源库"
        kind == "backup" || driveName == "Default" -> "备份盘"
        kind == "album" || driveName == "alibum" -> "相册"
        kind == "note_drive" -> "笔记"
        kind == "sbox" -> "保险箱"
        kind == "shared_album" -> "共享相册"
        else -> driveName.ifBlank { kind }
    }

    /**
     * 决定「音乐库」该用哪个盘：优先资源库（新版阿里云盘网页「文件」就在这），
     * 其次占用空间最大的盘，最后退回第一个。
     */
    suspend fun resolveLibrary(accessToken: String): Result<DriveTarget> =
        withContext(Dispatchers.IO) {
            runCatching {
                val drives = listMyDrives(accessToken).getOrThrow()
                if (drives.isEmpty()) error("账号下没有可用的盘")
                val picked = drives.firstOrNull { it.kind == "resource" }
                    ?: drives.filter { it.usedSize > 0 }.maxByOrNull { it.usedSize }
                    ?: drives.first()
                Log.i(TAG, "resolveLibrary -> ${picked.label}(${picked.kind}) drive=${picked.driveId} used=${picked.usedSize}")
                picked
            }.onFailure { Log.w(TAG, "resolveLibrary failed: $it") }
        }

    /** refresh_token 换 access_token（登录/续期同一入口）。 */
    suspend fun refreshAuth(refreshToken: String): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("refresh_token", refreshToken)
                    .put("grant_type", "refresh_token")
                    .toString()
                val req = okhttp3.Request.Builder()
                    .url(TOKEN_URL)
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Log.i(TAG, "refreshAuth raw: ${text.take(2500)}")
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                    val json = JSONObject(text)
                    AuthResult(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.getString("refresh_token"),
                        nickname = json.optString("user_name").takeIf { it.isNotEmpty() && it != "null" },
                        userId = json.optString("user_id").takeIf { it.isNotEmpty() && it != "null" }
                    )
                }
            }.onFailure { Log.w(TAG, "refreshAuth failed: $it") }
        }
}
