package com.xmgo.music.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 网盘扫描结果：一个目录下的一批音频文件。 */
data class CloudScanResult(
    val sourceType: CloudSourceType,
    val folderId: String,
    val folderName: String,
    val tracks: List<CloudTrack>,
    val folders: List<AliDriveApi.CloudFile> = emptyList(), // 子文件夹（下钻浏览）
    val isRoot: Boolean = true // 是否根目录（决定是否显示「返回根目录」）
)

/** 网盘音频文件（播放用）。 */
data class CloudTrack(
    val fileId: String,
    val name: String,      // 文件名（含扩展名）
    val size: Long,
    val sourceType: CloudSourceType
)

/**
 * 网盘音频扫描：列目录 → 过滤音频扩展名 → 缓存结果（首页卡片/目录页共用）。
 * 只扫一层（目录=歌单心智），不递归（性能/限流考虑）。
 */
object CloudScanManager {
    private const val TAG = "CloudScan"
    /** 支持识别的音频扩展名（小写）——只决定「算不算音乐」，不代表能播。 */
    val AUDIO_EXT = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "wma", "ape", "opus", "aiff", "dsf", "dff", "cue")

    /**
     * 当前**真能播出声**的扩展名。
     *
     * 前 7 个：ExoPlayer 原生解封装 + 解码。
     * 后面几个：容器 media3 没有 Extractor（ASF/APE/AIFF/WavPack/TAK/TTA），
     * 由 `FfmpegExtractor` 解封装 + `FfmpegAudioRenderer` 解码（见 ffmpeg-ext 模块）。
     * DSD(dsf/dff) 未编进 FFmpeg、cue 不是音频，仍不在内。
     */
    val PLAYABLE_EXT = setOf(
        // media3 原生
        "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus",
        // FFmpeg 扩展（自编 LGPL FFmpeg）
        "wma", "ape", "aiff", "aif", "wv", "tak", "tta"
    )

    /** 这个文件名（含扩展名）当前能不能真的播出声。 */
    fun isPlayable(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in PLAYABLE_EXT

    /** 扫描状态（首页卡片显示加载态）。 */
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** 最近扫描结果（主音源的，首页卡片显示）。 */
    private val _result = MutableStateFlow<CloudScanResult?>(null)
    val result: StateFlow<CloudScanResult?> = _result.asStateFlow()

    /** 已探测到的「有内容的盘」，按 userId 缓存（取直链/下钻都要用同一个 drive_id）。 */
    private val resolvedTargets = mutableMapOf<String, AliDriveApi.DriveTarget>()

    /**
     * 已换好的 access_token，key = 换取它用的 refresh_token。
     * 不能每次取直链都 refreshAuth：阿里会**轮换** refresh_token，频繁换会浪费请求；
     * 更要紧的是轮换后必须落盘（见 [auth]），否则存的旧 token 迟早失效、登录悄悄断掉。
     */
    private val authCache = mutableMapOf<String, Pair<AliDriveApi.AuthResult, Long>>()

    /** access_token 有效期 120 分钟，留 20 分钟余量。 */
    private const val AUTH_TTL_MS = 100 * 60 * 1000L

    /** 取可用的 access_token（带缓存；换到新 refresh_token 立即落盘）。 */
    private suspend fun auth(type: CloudSourceType): AliDriveApi.AuthResult {
        val token = CloudSourceManager.getRefreshToken(type)?.takeIf { it.isNotBlank() } ?: error("未登录")
        val now = System.currentTimeMillis()
        synchronized(authCache) {
            authCache[token]?.let { (cached, expiresAt) -> if (now < expiresAt) return cached }
        }
        val fresh = AliDriveApi.refreshAuth(token).getOrThrow()
        // 阿里轮换 refresh_token：不落盘的话，缓存过期后用旧 token 换新会失败 → 登录失效
        if (fresh.refreshToken != token) {
            CloudSourceManager.onLoggedIn(type, fresh.nickname, fresh.refreshToken)
        }
        synchronized(authCache) {
            authCache.entries.removeAll { it.value.second <= now }
            authCache[fresh.refreshToken] = fresh to (now + AUTH_TTL_MS)
        }
        return fresh
    }

    /** 解析当前账号该用哪个盘（带缓存）——阿里是多盘模型，不能用 default_drive_id。 */
    private suspend fun targetOf(auth: AliDriveApi.AuthResult): AliDriveApi.DriveTarget {
        val key = auth.userId ?: return AliDriveApi.resolveLibrary(auth.accessToken).getOrThrow()
        resolvedTargets[key]?.let { return it }
        return AliDriveApi.resolveLibrary(auth.accessToken).getOrThrow().also { resolvedTargets[key] = it }
    }

    /** 扫描主音源根目录（登录成功/刷新时调用）。 */
    suspend fun scanRoot() {
        val type = CloudSourceManager.primary.value ?: return
        _scanning.value = true
        try {
            val auth = auth(type)
            // 探测「有内容的盘」——阿里是多盘模型，默认盘可能就是空的
            val target = targetOf(auth)
            Log.i(TAG, "scanRoot target=${target.label} drive=${target.driveId} parent=${target.rootFolderId}")
            val files = AliDriveApi.listFiles(auth.accessToken, target.driveId, target.rootFolderId).getOrThrow()
            val audioFiles = files.filter {
                it.type == "file" && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
            }
            val folders = files.filter { it.type == "folder" }
            val tracks = audioFiles.map {
                CloudTrack(it.fileId, it.name, it.size, type)
            }
            _result.value = CloudScanResult(type, target.rootFolderId, "全部音乐", tracks, folders)
            Log.i(TAG, "scanRoot: ${folders.size} folders, ${tracks.size} audio files (type=$type)")
        } catch (e: Exception) {
            Log.w(TAG, "scanRoot failed: $e")
        } finally {
            _scanning.value = false
        }
    }

    /** 扫指定目录（目录页下钻用）。成功后直接更新 [result]，UI 立即切换。 */
    suspend fun scanFolder(folderId: String, folderName: String): CloudScanResult? =
        withContext(Dispatchers.IO) {
            val type = CloudSourceManager.primary.value ?: return@withContext null
            runCatching {
                val auth = auth(type)
                val target = targetOf(auth)
                val files = AliDriveApi.listFiles(auth.accessToken, target.driveId, folderId).getOrThrow()
                val audioFiles = files.filter {
                    it.type == "file" && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXT
                }
                val r = CloudScanResult(
                    type, folderId, folderName,
                    audioFiles.map { CloudTrack(it.fileId, it.name, it.size, type) },
                    files.filter { it.type == "folder" },
                    isRoot = false
                )
                Log.i(TAG, "scanFolder[$folderName]: ${r.folders.size} folders, ${r.tracks.size} audio files")
                _result.value = r
                r
            }.getOrNull()
        }

    /**
     * fileId → (直链, 过期时刻 epochMs)。
     * 按 **URL 里解析出的真实过期时间**失效（见 [OssExpiry]），提前 60s 就换新的。
     * 若拿已过期的链去播，CDN 返回 403，播放器会反复重载、跳转后进度归零。
     */
    private val urlCache = mutableMapOf<String, Pair<String, Long>>()

    /**
     * 流畅模式下取**服务端转码流**；返回 null 表示拿不到，调用方应**回落 FFmpeg 直解**。
     *
     * 转码中（`Transcoding`）也当「拿不到」处理——直接回落比让用户等更顺。
     * 结果按真实过期时间缓存（转码流同样是限时签名 URL）。
     */
    suspend fun getFlowUrl(type: CloudSourceType, fileId: String): String? =
        withContext(Dispatchers.IO) {
            val cacheKey = "flow:$fileId"
            val now = System.currentTimeMillis()
            synchronized(urlCache) { urlCache[cacheKey] }?.let { (cached, expiresAt) ->
                if (now < expiresAt - 60_000) return@withContext cached
            }
            runCatching {
                val auth = auth(type)
                val target = targetOf(auth)
                when (val info = AliDriveApi.getAudioPlayInfo(auth.accessToken, target.driveId, fileId).getOrThrow()) {
                    is AliDriveApi.AudioPlayInfo.Ready -> {
                        val realExpiry = OssExpiry.expiryTimeMs(info.url)
                        val expiresAt = if (realExpiry > 0) realExpiry else now + 14_400_000L
                        synchronized(urlCache) {
                            if (urlCache.size >= 16) urlCache.entries.iterator().let {
                                while (it.hasNext() && urlCache.size >= 16) it.next().let { _ -> it.remove() }
                            }
                            urlCache[cacheKey] = info.url to expiresAt
                        }
                        Log.i(TAG, "getFlowUrl: 取到 ${info.templateId} 转码流")
                        info.url
                    }

                    is AliDriveApi.AudioPlayInfo.Transcoding -> {
                        Log.i(TAG, "getFlowUrl: 转码中，回落 FFmpeg")
                        null
                    }

                    is AliDriveApi.AudioPlayInfo.Unavailable -> {
                        Log.i(TAG, "getFlowUrl: 无转码信息，回落 FFmpeg")
                        null
                    }
                }
            }.getOrElse {
                Log.w(TAG, "getFlowUrl failed: $it")
                null
            }
        }

    /** 播放时取直链（15 分钟限时，播到才取——见 [CloudResolvingDataSource]）。 */
    suspend fun getTrackUrl(type: CloudSourceType, fileId: String): Result<String> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            synchronized(urlCache) { urlCache[fileId] }?.let { (cached, expiresAt) ->
                if (now < expiresAt - 60_000) return@withContext Result.success(cached)
            }
            runCatching {
                val auth = auth(type)
                val target = targetOf(auth)
                val url = AliDriveApi.getDownloadUrl(auth.accessToken, target.driveId, fileId).getOrThrow()
                val realExpiry = OssExpiry.expiryTimeMs(url)
                val expiresAt = if (realExpiry > 0) realExpiry else now + 14_400_000L
                synchronized(urlCache) {
                    // 只留少量：同目录连播时够用，不占内存
                    if (urlCache.size >= 16) urlCache.entries.iterator().let {
                        while (it.hasNext() && urlCache.size >= 16) it.next().let { _ -> it.remove() }
                    }
                    urlCache[fileId] = url to expiresAt
                }
                url
            }
        }

    /** 播放时取直链（15 分钟限时，每次实时取）。 */
    suspend fun getTrackUrl(track: CloudTrack): Result<String> =
        getTrackUrl(track.sourceType, track.fileId)
}
