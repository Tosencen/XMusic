package com.xmgo.music.data.cache

import android.content.Context
import android.net.Uri
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Lyrics
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 缓存时顺带拉取歌词的限时（超时则跳过，音频缓存照常成功）。 */
private const val LYRICS_FETCH_TIMEOUT_MS = 15_000L

/** 已缓存歌曲的索引条目（song 元数据 + 文件名 + 所属分组）。 */
@Serializable
data class CachedSongEntry(
    val song: Song,
    val fileName: String,
    val sizeBytes: Long = 0,
    val cachedAt: Long = 0,
    /** 所属分组 id，null = 未分组。 */
    val groupId: String? = null
)

/** 自建分组。 */
@Serializable
data class CachedMusicGroup(
    val id: String,
    val name: String
)

/** 下载进度：received 已收字节，total 总字节（-1 = 服务端未提供）。 */
data class CacheProgress(
    val received: Long,
    val total: Long
) {
    val fraction: Float
        get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else -1f
}

/** 整批下载进度（歌单/专辑批量缓存）。mediaIds = 本批待下载的 mediaId 集合。 */
data class BatchProgress(
    val total: Int,
    val done: Int,
    val failed: Int,
    val mediaIds: Set<String>
) {
    val fraction: Float
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/**
 * 在线歌曲本地缓存：resolveStream 取流地址 → 下载到 filesDir/cached_music，
 * index.json 记录元数据。播放时 [localUriFor] 命中则直接用本地文件（免解析、免联网）。
 */
class CachedMusicStore(
    private val context: Context,
    private val pluginManager: PluginManager
) {

    private val dir = File(context.filesDir, "cached_music").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val groupsFile = File(dir, "groups.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // 初始为空列表，首次访问在 IO 线程懒加载（避免 Application 主线程同步读盘解析 JSON）
    private val _cached = MutableStateFlow<List<CachedSongEntry>>(emptyList())
    val cached: StateFlow<List<CachedSongEntry>> = _cached.asStateFlow()

    private val _groups = MutableStateFlow<List<CachedMusicGroup>>(emptyList())
    val groups: StateFlow<List<CachedMusicGroup>> = _groups.asStateFlow()

    /** UI 无关的下载作用域：不随弹层/页面销毁取消。 */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 索引/分组加载完成信号：所有读写索引的挂起路径先 await，防止加载完成前的写入覆盖磁盘数据。 */
    private val stateLoaded = CompletableDeferred<Unit>()

    init {
        storeScope.launch {
            _cached.value = loadIndex()
            _groups.value = loadGroups()
            // 加载后、stateLoaded 放行前清理：此时尚无下载在写文件（下载路径先 await stateLoaded）
            cleanupOrphanFiles()
            stateLoaded.complete(Unit)
        }
    }

    /**
     * 清理孤儿文件：
     * - `*.part`：进程被杀/崩溃残留的未完成下载（正常取消/失败路径会自行删除）；
     * - 不在 index.json 里的音频文件：崩溃发生在 rename 成功之后、索引落盘之前时会留下
     *   这种"完整但未入索引"的文件——index.json 是唯一事实来源，留着只会白占空间。
     * 歌词 sidecar（`<sha1>.lyrics.json`）按其音频是否在索引里决定去留。
     */
    private fun cleanupOrphanFiles() {
        val indexedNames = _cached.value.mapTo(HashSet()) { it.fileName }
        val indexedBases = indexedNames.mapTo(HashSet()) { it.substringBefore('.') }
        dir.listFiles()?.forEach { f ->
            val n = f.name
            when {
                n.endsWith(".part") -> f.delete()
                n == indexFile.name || n == groupsFile.name -> Unit
                n.endsWith(".lyrics.json") -> {
                    if (n.removeSuffix(".lyrics.json") !in indexedBases) f.delete()
                }
                f.isFile && n !in indexedNames -> f.delete()
            }
        }
    }

    /** 正在下载中的 mediaId 集合（UI 展示"缓存中…"）。 */
    private val _cachingIds = MutableStateFlow<Set<String>>(emptySet())
    val cachingIds: StateFlow<Set<String>> = _cachingIds.asStateFlow()

    /** mediaId → 下载进度（UI 展示进度条）。 */
    private val _progress = MutableStateFlow<Map<String, CacheProgress>>(emptyMap())
    val progress: StateFlow<Map<String, CacheProgress>> = _progress.asStateFlow()

    /** 缓存结果事件（"已缓存「xx」/缓存失败「xx」"），UI 收集后 Toast。 */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** 批量下载进度（null = 无进行中的批次）。 */
    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress: StateFlow<BatchProgress?> = _batchProgress.asStateFlow()

    private var batchJob: Job? = null

    /** 后台缓存（fire-and-forget，幂等）。进度经 [progress] 流、结果经 [events] 流观察。 */
    fun cacheAsync(song: Song) {
        val mediaId = mediaIdOf(song)
        if (mediaId in _cachingIds.value || isCached(mediaId)) return
        storeScope.launch {
            _cachingIds.update { it + mediaId }
            try {
                val result = cache(song)
                if (result.isSuccess) {
                    _events.tryEmit("已缓存「${song.name}」")
                } else {
                    _events.tryEmit("缓存失败「${song.name}」")
                }
            } catch (t: Throwable) {
                _events.tryEmit("缓存失败「${song.name}」")
            } finally {
                _cachingIds.update { it - mediaId }
                _progress.update { it - mediaId }
            }
        }
    }

    fun mediaIdOf(song: Song): String = FavoritesStore.mediaIdOf(song)

    /**
     * 批量下载整批歌（跳过已缓存；单曲失败不中断）。进度经 [batchProgress]，
     * 结果聚合为一条 [events] 提示。已有批次进行中时忽略并提示。
     */
    fun cacheAll(songs: List<Song>) {
        if (batchJob?.isActive == true) {
            _events.tryEmit("已有下载任务进行中")
            return
        }
        val pending = songs.distinctBy { mediaIdOf(it) }.filterNot { isCached(mediaIdOf(it)) }
        if (pending.isEmpty()) {
            _events.tryEmit("歌曲已全部缓存")
            return
        }
        val pendingIds = pending.map { mediaIdOf(it) }.toSet()
        _batchProgress.value = BatchProgress(
            total = pending.size, done = 0, failed = 0, mediaIds = pendingIds
        )
        _events.tryEmit("开始下载 ${pending.size} 首")
        batchJob = storeScope.launch {
            var done = 0
            var failed = 0
            try {
                for (song in pending) {
                    ensureActive()
                    val mediaId = mediaIdOf(song)
                    val ok = try {
                        cache(song).isSuccess
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        false
                    }
                    if (ok) done++ else failed++
                    _progress.update { it - mediaId }
                    _batchProgress.value = BatchProgress(
                        total = pending.size, done = done, failed = failed, mediaIds = pendingIds
                    )
                }
                _events.tryEmit(
                    if (failed == 0) "下载完成：$done 首"
                    else "下载结束：$done 成功，$failed 失败"
                )
            } catch (e: CancellationException) {
                _events.tryEmit("已取消下载（已完成 $done 首）")
                throw e
            } finally {
                pendingIds.forEach { id -> _progress.update { it - id } }
                _batchProgress.update { cur ->
                    if (cur?.mediaIds == pendingIds) null else cur
                }
            }
        }
    }

    /** 取消批量下载：进行中的当前一首随协程取消停止，已下载的保留。 */
    fun cancelBatch() {
        batchJob?.cancel()
    }

    fun isCached(mediaId: String): Boolean = _cached.value.any { mediaIdOf(it.song) == mediaId }

    /** 本地缓存文件（不存在返回 null）。 */
    fun fileFor(mediaId: String): File? {
        val entry = _cached.value.firstOrNull { mediaIdOf(it.song) == mediaId } ?: return null
        val file = File(dir, entry.fileName)
        return if (file.isFile && file.length() > 0) file else null
    }

    /** 命中缓存返回本地 file:// Uri，否则 null。 */
    fun localUriFor(mediaId: String): Uri? = fileFor(mediaId)?.let(Uri::fromFile)

    /** 歌词 sidecar 文件（<sha1(mediaId)>.lyrics.json），与音频文件同名不同后缀。 */
    private fun lyricsFileFor(mediaId: String): File = File(dir, sha1(mediaId) + ".lyrics.json")

    /** 读取缓存的原始歌词（无文件/解析失败返回 null，调用方回退插件联网取词）。 */
    fun readLyrics(mediaId: String): Lyrics? {
        val file = lyricsFileFor(mediaId)
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString<Lyrics>(file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    /** 落盘原始歌词（存原始形态而非解析结果：yrc 逐字 / 翻译离线重放不丢）。 */
    fun saveLyrics(mediaId: String, lyrics: Lyrics) {
        runCatching {
            lyricsFileFor(mediaId).writeText(json.encodeToString(lyrics), Charsets.UTF_8)
        }
    }

    /** 同一 mediaId 的在途下载去重（全局锁拆分后防止同一首歌并发重复下载）。 */
    private val inFlightDownloads = ConcurrentHashMap.newKeySet<String>()

    /**
     * 下载缓存（幂等：已缓存直接返回）。返回文件大小，失败抛异常。
     * 锁粒度：网络传输不持全局锁（否则删除/改分组/新下载全被阻塞到当前歌曲下载完），
     * 仅索引提交段持 [mutex]；同一 mediaId 用 [inFlightDownloads] 去重。
     */
    suspend fun cache(song: Song): Result<Long> = withContext(Dispatchers.IO) {
        val mediaId = mediaIdOf(song)
        stateLoaded.await()
        fileFor(mediaId)?.let { return@withContext Result.success(it.length()) }
        if (!inFlightDownloads.add(mediaId)) {
            // 同一首歌已在下载：等待其结果语义复杂，直接按"已缓存/失败"以外的幂等成功跳过
            fileFor(mediaId)?.let { return@withContext Result.success(it.length()) }
            return@withContext Result.failure(IllegalStateException("该歌曲正在下载中"))
        }
        try {
            runCatching {
                val stream = pluginManager.providerFor(song.sourceId).resolveStream(song)
                val ext = extensionFor(stream.mimeType, stream.url)
                val fileName = sha1(mediaId) + ext
                val tmp = File(dir, "$fileName.part")
                val target = File(dir, fileName)
                try {
                    val request = Request.Builder().url(stream.url).apply {
                        stream.headers.forEach { (k, v) -> header(k, v) }
                    }.build()
                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
                        val body = resp.body ?: error("下载失败：空响应")
                        val total = body.contentLength() // -1 = 未知
                        _progress.update {
                            it + (mediaId to CacheProgress(received = 0, total = total))
                        }
                        tmp.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var received = 0L
                                var lastEmit = 0L
                                while (true) {
                                    // 协作取消点：批量下载取消时尽快停止传输
                                    if (!coroutineContext.isActive) throw CancellationException()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    received += n
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmit >= 150) {
                                        lastEmit = now
                                        _progress.update {
                                            it + (mediaId to CacheProgress(received, total))
                                        }
                                    }
                                }
                                _progress.update {
                                    it + (mediaId to CacheProgress(received, total.coerceAtLeast(0)))
                                }
                            }
                        }
                    }
                    if (!tmp.renameTo(target)) {
                        target.delete()
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    // 索引提交段：短锁，不阻塞其他歌的网络下载
                    mutex.withLock {
                        val entry = CachedSongEntry(
                            song = song,
                            fileName = fileName,
                            sizeBytes = target.length(),
                            cachedAt = System.currentTimeMillis(),
                            groupId = _cached.value
                                .firstOrNull { mediaIdOf(it.song) == mediaId }?.groupId
                        )
                        val next = _cached.value.filterNot { mediaIdOf(it.song) == mediaId } + entry
                        persist(next)
                    }
                    // 歌词一并缓存（best-effort：拉词失败不影响音频缓存结果）
                    runCatching {
                        withTimeoutOrNull(LYRICS_FETCH_TIMEOUT_MS) {
                            val lyrics = pluginManager.providerFor(song.sourceId).lyrics(song)
                            if (lyrics != null) saveLyrics(mediaId, lyrics)
                        }
                    }
                    target.length()
                } catch (t: Throwable) {
                    tmp.delete()
                    throw t
                }
            }.onFailure {
                // 取消不是失败：向上抛出，让批量循环的 CancellationException 分支收尾
                if (it is CancellationException) throw it
                android.util.Log.w("CachedMusicStore", "缓存失败「${song.name}」", it)
            }
        } finally {
            inFlightDownloads.remove(mediaId)
        }
    }

    suspend fun remove(mediaId: String): Unit = withContext(Dispatchers.IO) {
        stateLoaded.await()
        mutex.withLock {
            val entry = _cached.value.firstOrNull { mediaIdOf(it.song) == mediaId }
                ?: return@withLock
            File(dir, entry.fileName).delete()
            lyricsFileFor(mediaId).delete()
            persist(_cached.value.filterNot { mediaIdOf(it.song) == mediaId })
        }
    }

    suspend fun removeMany(mediaIds: Collection<String>): Unit =
        mediaIds.forEach { remove(it) }

    // ---------------------------------------------------------------- 分组

    suspend fun createGroup(name: String): String = withContext(Dispatchers.IO) {
        stateLoaded.await()
        mutex.withLock {
            val id = "g_${System.currentTimeMillis()}"
            val next = _groups.value + CachedMusicGroup(id = id, name = name.trim())
            persistGroups(next)
            id
        }
    }

    suspend fun renameGroup(id: String, name: String): Unit = withContext(Dispatchers.IO) {
        stateLoaded.await()
        mutex.withLock {
            persistGroups(_groups.value.map {
                if (it.id == id) it.copy(name = name.trim()) else it
            })
        }
    }

    /** 删除分组：组内歌曲回到未分组（保留缓存文件）。 */
    suspend fun deleteGroup(id: String): Unit = withContext(Dispatchers.IO) {
        stateLoaded.await()
        mutex.withLock {
            persistGroups(_groups.value.filterNot { it.id == id })
            persist(_cached.value.map {
                if (it.groupId == id) it.copy(groupId = null) else it
            })
        }
    }

    /** 把歌曲移动到分组（groupId = null 移回未分组）。 */
    suspend fun moveToGroup(mediaIds: Collection<String>, groupId: String?): Unit =
        withContext(Dispatchers.IO) {
            stateLoaded.await()
            mutex.withLock {
                val ids = mediaIds.toSet()
                persist(_cached.value.map {
                    if (mediaIdOf(it.song) in ids) it.copy(groupId = groupId) else it
                })
            }
        }

    // ---------------------------------------------------------------- 内部

    private fun loadIndex(): List<CachedSongEntry> {
        val candidates = listOf(indexFile, File(dir, "index.json.bak"))
        for (file in candidates) {
            if (!file.isFile) continue
            runCatching {
                json.decodeFromString<List<CachedSongEntry>>(file.readText(Charsets.UTF_8))
            }.onSuccess {
                if (file != indexFile) {
                    // 备份恢复成功：立即回写主文件，后续正常走主文件路径
                    writeAtomically(indexFile, json.encodeToString(it))
                }
                return it
            }.onFailure {
                android.util.Log.w("CachedMusicStore", "${file.name} 解析失败，尝试下一来源", it)
            }
        }
        return emptyList()
    }

    /** 先写临时文件再 rename 覆盖，避免进程被杀/磁盘满留下截断的索引文件。 */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            // rename 失败（个别文件系统）：退化为直接写 + 备份旧文件
            runCatching {
                if (target.isFile) target.copyTo(File(target.parentFile, target.name + ".bak"), overwrite = true)
                tmp.copyTo(target, overwrite = true)
            }
            tmp.delete()
        }
    }

    private fun persist(entries: List<CachedSongEntry>) {
        _cached.value = entries
        runCatching {
            writeAtomically(indexFile, json.encodeToString(entries))
        }.onFailure {
            android.util.Log.w("CachedMusicStore", "index.json 写入失败", it)
        }
    }

    private fun loadGroups(): List<CachedMusicGroup> {
        if (!groupsFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<CachedMusicGroup>>(groupsFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: emptyList()
    }

    private fun persistGroups(groups: List<CachedMusicGroup>) {
        _groups.value = groups
        runCatching {
            writeAtomically(groupsFile, json.encodeToString(groups))
        }.onFailure {
            android.util.Log.w("CachedMusicStore", "groups.json 写入失败", it)
        }
    }

    private fun sha1(input: String): String = MessageDigest.getInstance("SHA-1")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun extensionFor(mimeType: String?, url: String): String {
        val fromMime = when (mimeType?.substringBefore('/')?.lowercase()) {
            "audio" -> when (mimeType.substringAfter('/').substringBefore(';').lowercase()) {
                "mpeg", "mp3" -> ".mp3"
                "flac" -> ".flac"
                "mp4", "m4a", "aac" -> ".m4a"
                "ogg", "opus" -> ".ogg"
                "wav" -> ".wav"
                else -> null
            }
            else -> null
        }
        if (fromMime != null) return fromMime
        val path = url.substringBefore('?').substringBefore('#')
        val fromUrl = path.substringAfterLast('.', "").lowercase()
        if (fromUrl in setOf("mp3", "flac", "m4a", "ogg", "wav", "aac", "opus")) {
            return ".$fromUrl"
        }
        return ".mp3"
    }
}
