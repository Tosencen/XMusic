package com.xmgo.music.plugin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * .qplug 插件包内的 plugin.json 清单（ABI v1）。
 * 与 qplayer 的 plugin.json 字段保持兼容，直接解析原版插件。
 */
@Serializable
data class PluginManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: String = "1.0",
    val minHostVersion: String? = null,
    val entry: String = "main.js",
    val publisher: String? = null,
    val capabilities: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val networkDomains: List<String> = emptyList(),
    val networkMethods: List<String> = emptyList(),
    val ui: List<PluginUiEntry> = emptyList()
)

/** plugin.json 的 ui[] 条目：声明插件贡献给宿主 UI 的入口（QML 侧实现，Compose 侧降级处理）。 */
@Serializable
data class PluginUiEntry(
    val id: String,
    val placement: String,
    val label: String? = null,
    val icon: String? = null
)

/**
 * 已安装插件的运行时状态。
 * @param dir 解包后的私有目录
 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val dir: java.io.File
) {
    val id: String get() = manifest.id
    val name: String get() = manifest.name
}

/** 插件能力常量（与 qplayer ABI v1 capability 名一致）。 */
object Capabilities {
    const val SEARCH_SONGS = "searchSongs"
    const val SEARCH_ALBUMS = "searchAlbums"
    const val SEARCH_ARTISTS = "searchArtists"
    const val HOT_SEARCH = "hotSearch"
    const val HOME = "home"
    const val SONG_DETAILS = "songDetails"
    const val PLAYLIST_DETAILS = "playlistDetails"
    const val ARTIST_DETAILS = "artistDetails"
    const val ALBUM_DETAILS = "albumDetails"
    const val RECENT = "recent"
    const val RESOLVE_STREAM = "resolveStream"
    const val LYRICS = "lyrics"
    const val ACCOUNT = "account"
    const val USER_PLAYLISTS = "userPlaylists"
    const val SCROBBLE = "scrobble"
    const val LIKE = "like"
    const val PLAYLIST_MUTATION = "playlistMutation"
    const val PLAYLIST_COVER = "playlistCover"
    const val HEART_RECOMMENDATION = "heartRecommendation"
    const val SHARE = "share"
    const val LOGIN = "login"
}
