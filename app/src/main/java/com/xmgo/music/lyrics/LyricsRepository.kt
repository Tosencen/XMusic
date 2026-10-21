package com.xmgo.music.lyrics

import android.content.Context
import android.provider.MediaStore
import android.util.LruCache
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Capabilities
import com.xmgo.music.plugin.model.Lyrics
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 歌词解析入口。
 * 本地歌：查 MediaStore 文件路径 → 同名 .lrc；
 * 在线歌（mediaId = sourceId:rawId）：走插件 lyrics handler，翻译按时间戳合并。
 *
 * 跨源兜底：本源无词时，用 [title]/[artist] 搜索其它具备 lyrics+searchSongs
 * 能力的插件，按歌名/歌手匹配后取词（结果仍按 mediaId 缓存）。
 *
 * 解析结果做内存 LRU 缓存（对齐 qplayer lyricMem 思路）：
 * 切歌时 [prefetch] 预取当前/相邻曲目，进播放页即刻出词，不再等网络。
 */
class LyricsRepository(
    private val context: Context,
    private val pluginManager: PluginManager
) {

    /** 预取协程作用域（IO）。 */
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 缓存空结果同样入缓存：无歌词的歌不必每次重新请求插件
    private val cache = object : LruCache<String, List<LyricLine>>(32) {}

    suspend fun load(
        mediaId: String,
        title: String? = null,
        artist: String? = null
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        cache.get(mediaId)?.let { return@withContext it }
        val lines = when {
            mediaId.startsWith(LOCAL_PREFIX) ->
                loadLocal(mediaId.removePrefix(LOCAL_PREFIX).toLongOrNull())
            else ->
                loadPlugin(mediaId, title, artist)
        } ?: emptyList()
        cache.put(mediaId, lines)
        lines
    }

    /** 后台预取（不阻塞调用方）；已在缓存中的直接跳过。 */
    fun prefetch(mediaId: String, title: String? = null, artist: String? = null) {
        if (mediaId.isBlank() || cache.get(mediaId) != null) return
        android.util.Log.d("LyricsRepository", "prefetch $mediaId")
        prefetchScope.launch { runCatching { load(mediaId, title, artist) } }
    }

    // ---------------------------------------------------------------- 本地

    private fun loadLocal(songId: Long?): List<LyricLine>? {
        if (songId == null) return null
        val path = queryFilePath(songId) ?: return null
        val base = path.substringAfterLast('/').substringBeforeLast('.')
        val dir = path.substringBeforeLast('/', "")
        if (dir.isEmpty()) return null

        // 同名 .lrc（忽略大小写）
        val candidates = listOf("$base.lrc", "$base.LRC")
        val lrcFile = candidates
            .map { File(dir, it) }
            .firstOrNull { it.isFile && it.canRead() }
            ?: return null

        val lines = LrcParser.parse(lrcFile.readText(Charsets.UTF_8))
        return lines.ifEmpty { null }
    }

    private fun queryFilePath(songId: Long): String? = runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.DATA),
            "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(songId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 插件

    private suspend fun loadPlugin(
        mediaId: String,
        title: String?,
        artist: String?
    ): List<LyricLine>? {
        if (':' !in mediaId) return null
        val sourceId = mediaId.substringBefore(':')
        val rawId = mediaId.substringAfter(':')
        if (sourceId.isEmpty() || rawId.isEmpty()) return null

        // 1) 本源优先：rawId 直查，不联网搜索
        val own = runCatching {
            val provider = pluginManager.providerFor(sourceId)
            val lyrics = provider.lyrics(
                Song(
                    id = mediaId,
                    name = "",
                    sourceId = sourceId,
                    rawId = rawId
                )
            )
            parseLyrics(lyrics)
        }.getOrNull()
        if (!own.isNullOrEmpty()) return own

        // 2) 跨源兜底：本源无词（或插件无 lyrics 能力），搜其它插件取词
        if (title.isNullOrBlank()) return own
        android.util.Log.d("LyricsRepository", "own-source lyrics miss ($sourceId:$rawId), trying cross-source")
        return crossSourceLyrics(ownSourceId = sourceId, title = title, artist = artist)
    }

    /**
     * 跨插件取词：按安装顺序遍历其它同时具备 [Capabilities.LYRICS] +
     * [Capabilities.SEARCH_SONGS] 的插件，以「歌名 歌手」搜索，歌名/歌手
     * 规范化匹配后取该插件自己的 rawId 查词，首个非空即返回。
     * 整段限时，超时放弃（本源已无词，宁缺毋滥）。
     */
    private suspend fun crossSourceLyrics(
        ownSourceId: String,
        title: String,
        artist: String?
    ): List<LyricLine>? = withTimeoutOrNull(CROSS_SOURCE_TIMEOUT_MS) {
        val keyword = if (artist.isNullOrBlank()) title else "$title $artist"
        for (plugin in pluginManager.plugins) {
            if (plugin.id == ownSourceId) continue
            val caps = plugin.manifest.capabilities
            if (Capabilities.LYRICS !in caps || Capabilities.SEARCH_SONGS !in caps) continue
            val lines = runCatching {
                val provider = pluginManager.providerFor(plugin.id)
                val match = provider.searchSongs(keyword).items
                    .firstOrNull { matchesTitleArtist(it, title, artist) }
                    ?: return@runCatching null
                parseLyrics(provider.lyrics(match))
            }.getOrNull()
            if (!lines.isNullOrEmpty()) {
                android.util.Log.d("LyricsRepository", "cross-source lyrics hit: plugin=${plugin.id} lines=${lines.size}")
                return@withTimeoutOrNull lines
            }
        }
        null
    }

    /** 歌名规范化匹配（忽略大小写/空白/括号备注/标点）；歌手双方都有时需互相包含。 */
    private fun matchesTitleArtist(candidate: Song, title: String, artist: String?): Boolean {
        val normTitle = normalizeForMatch(title)
        val normName = normalizeForMatch(candidate.name)
        if (normTitle.isEmpty() || normName.isEmpty()) return false
        if (normName != normTitle && !normName.contains(normTitle) && !normTitle.contains(normName)) return false
        if (artist.isNullOrBlank()) return true
        val normArtist = normalizeForMatch(artist)
        if (normArtist.isEmpty()) return true
        val normCandArtists = candidate.artists.joinToString(" ") { normalizeForMatch(it) }
        return normCandArtists.isEmpty() ||
            normCandArtists.contains(normArtist) ||
            normArtist.contains(normCandArtists)
    }

    /** 去括号备注（(Live)/【伴奏】等）+ 转小写 + 去标点空白，用于跨源歌名匹配。 */
    private fun normalizeForMatch(s: String): String = s
        .replace(Regex("""[\[【(（][^\]】)）]*[\]】)）]"""), " ")
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")
        .trim()

    private fun parseLyrics(lyrics: Lyrics?): List<LyricLine>? {
        lyrics ?: return null
        val original = when (lyrics.originalFormat?.lowercase()) {
            "yrc" -> LrcParser.yrcToLrc(lyrics.original)
            "ttml" -> LrcParser.ttmlToLrc(lyrics.original) ?: lyrics.original
            else -> lyrics.original
        }
        return LrcParser.parseWithTranslation(original, lyrics.translation)
            .ifEmpty { null }
    }

    companion object {
        const val LOCAL_PREFIX = "local:"

        /** 跨源搜词整体限时：防止逐插件 60s handler 超时把播放页歌词卡死。 */
        private const val CROSS_SOURCE_TIMEOUT_MS = 15_000L
    }
}
