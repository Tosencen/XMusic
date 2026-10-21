package com.xmgo.music.plugin

import com.xmgo.music.plugin.model.HomeContent
import com.xmgo.music.plugin.model.Lyrics
import com.xmgo.music.plugin.model.OnlineAlbum
import com.xmgo.music.plugin.model.OnlineArtist
import com.xmgo.music.plugin.model.OnlineCollection
import com.xmgo.music.plugin.model.PagedItems
import com.xmgo.music.plugin.model.SearchResult
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.plugin.model.StreamInfo

/**
 * 音源能力接口：插件 handler 的 Kotlin 侧门面。
 * 每个实现由 PluginRuntime 动态代理到 JS handler；未声明能力的调用抛 UnsupportedOperationException。
 */
interface MusicSourceProvider {
    /** 来源插件 id */
    val sourceId: String

    /** 来源展示名（如 "网易云音乐"） */
    val sourceName: String

    suspend fun searchSongs(keyword: String, cursor: String? = null): SearchResult

    suspend fun searchAlbums(keyword: String, cursor: String? = null): PagedItems<OnlineAlbum>

    suspend fun searchArtists(keyword: String, cursor: String? = null): PagedItems<OnlineArtist>

    /** 热搜词（hotSearch）。 */
    suspend fun hotSearch(): List<String>

    /** 首页内容（home）：推荐歌单 + 每日歌曲 + 分组货架。 */
    suspend fun home(limit: Int = 12): HomeContent

    /** 歌单详情（playlistDetails）。 */
    suspend fun playlistDetails(id: String): OnlineCollection

    /** 专辑详情（albumDetails）。 */
    suspend fun albumDetails(id: String): OnlineCollection

    /** 歌手详情（artistDetails）。 */
    suspend fun artistDetails(id: String): OnlineCollection

    suspend fun resolveStream(song: Song): StreamInfo

    suspend fun lyrics(song: Song): Lyrics?
}
