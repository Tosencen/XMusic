package com.xmgo.music.plugin.model

import kotlinx.serialization.Serializable

/** 统一歌曲模型：插件 handler 返回的 JSON 归一化后流入 UI / Media3。 */
@Serializable
data class Song(
    val id: String,
    val name: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long = 0,
    val artworkUrl: String? = null,
    /** 来源插件 id（如 netease） */
    val sourceId: String,
    /** 插件内原始 song id（resolveStream 用） */
    val rawId: String = id
)

/** 搜索结果分页包装。 */
data class SearchResult(
    val items: List<Song>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

/** 播放地址解析结果。 */
data class StreamInfo(
    val url: String,
    val mimeType: String? = null,
    val bitrateKbps: Int? = null,
    val headers: Map<String, String> = emptyMap()
)

/** 歌词（原文 + 可选翻译）。 */
data class Lyrics(
    val original: String,
    val translation: String? = null,
    /** 原文格式：lrc | yrc | ttml | null（未知，按 lrc 尝试）——QPlayer ABI assets 协议 */
    val originalFormat: String? = null
)

/** 歌手引用（专辑/歌单内联的 {id,name}）。 */
data class ArtistRef(
    val id: String,
    val name: String
)

/** 在线歌单卡片（首页 sections / 推荐歌单 / 搜索结果）。 */
data class OnlinePlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val ownerName: String? = null,
    val trackCount: Int = 0,
    val playCount: Int = 0
)

/** 在线专辑。 */
data class OnlineAlbum(
    val id: String,
    val name: String,
    val artworkUrl: String? = null,
    val artists: List<ArtistRef> = emptyList(),
    val trackCount: Int = 0,
    val publishTimeMs: Long = 0
)

/** 在线歌手。 */
data class OnlineArtist(
    val id: String,
    val name: String,
    val artworkUrl: String? = null,
    val description: String = "",
    val albumCount: Int = 0,
    val songCount: Int = 0
)

/** 通用分页结果。 */
data class PagedItems<T>(
    val items: List<T>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false
)

/** 首页 home handler 的一个分组货架（如「雷达歌单」）。 */
data class HomeSection(
    val title: String,
    val playlists: List<OnlinePlaylist>
)

/** 首页内容：推荐歌单 + 每日歌曲 + 分组货架。 */
data class HomeContent(
    val songs: List<Song> = emptyList(),
    val playlists: List<OnlinePlaylist> = emptyList(),
    val sections: List<HomeSection> = emptyList()
)

/** 在线集合详情（歌单 / 专辑 / 歌手通用：头部元数据 + 歌曲 + 可选专辑）。 */
data class OnlineCollection(
    val kind: String,
    val id: String,
    val name: String,
    val description: String = "",
    val artworkUrl: String? = null,
    val ownerName: String? = null,
    val songs: List<Song> = emptyList(),
    val albums: List<OnlineAlbum> = emptyList()
)

/** 登录方式（login handler → operation=methods）。 */
data class LoginMethod(
    val id: String,
    val type: String,          // qr / web / credential / app
    val label: String,
    val instructions: String = "",
    val webUrl: String = "",
    val cookieUrl: String = "",
    val credentialLabel: String = "Cookie"
)

/** 登录挑战状态（begin / poll / submit 的返回）。 */
data class LoginChallenge(
    val id: String = "",
    val methodId: String = "",
    /** waiting / scanned / success / expired / failed */
    val status: String,
    val qrContent: String = "",
    val message: String = ""
)

/** 账号信息（account handler）。 */
data class AccountInfo(
    val loggedIn: Boolean,
    val id: String = "",
    val displayName: String = "",
    val avatarUrl: String = "",
    val level: Int = 0
)
