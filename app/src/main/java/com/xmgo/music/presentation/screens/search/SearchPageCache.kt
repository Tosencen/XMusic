package com.xmgo.music.presentation.screens.search

import com.xmgo.music.plugin.model.OnlineAlbum
import com.xmgo.music.plugin.model.OnlineArtist
import com.xmgo.music.plugin.model.Song

/** 歌手搜索结果（带来源插件，进详情需要 sourceId）。 */
data class ArtistHit(val sourceId: String, val artist: OnlineArtist)

/** 专辑搜索结果（带来源插件）。 */
data class AlbumHit(val sourceId: String, val album: OnlineAlbum)

/** 一次搜索结果快照（按 query+tab 缓存，返回搜索页时免重搜）。 */
data class SearchSnapshot(
    val songs: List<Song> = emptyList(),
    val artists: List<ArtistHit> = emptyList(),
    val albums: List<AlbumHit> = emptyList(),
    val songCursors: Map<String, String?> = emptyMap(),
    val artistCursors: Map<String, String?> = emptyMap(),
    val albumCursors: Map<String, String?> = emptyMap(),
    val error: String? = null
)

/**
 * 搜索页缓存（进程内单例）：
 * 热搜词只拉一次；query 结果按 query+tab 缓存，返回页面不再重搜。
 */
class SearchPageCache {
    @Volatile var hotWords: List<String> = emptyList()
    @Volatile var hotWordsLoaded: Boolean = false

    private val results = LinkedHashMap<String, SearchSnapshot>(8, 0.75f, true)

    fun snapshot(query: String, tab: Int): SearchSnapshot? =
        synchronized(results) { results["$query|$tab"] }

    fun put(query: String, tab: Int, snapshot: SearchSnapshot) {
        synchronized(results) {
            results["$query|$tab"] = snapshot
            while (results.size > 12) {
                val eldest = results.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
    }
}
