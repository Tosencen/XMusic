package com.xmgo.music.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed class Screen : NavKey {
    @Serializable data object Home : Screen()
    @Serializable data object Search : Screen()
    @Serializable data object Settings : Screen()
    @Serializable data object SettingsLookAndFeel : Screen()

    /** 悬浮歌词设置（设置主页入口）。 */
    @Serializable data object OverlayLyricSettings : Screen()
    @Serializable data object Plugins : Screen()
    @Serializable data object About : Screen()

    /** 最近播放。 */
    @Serializable data object Recent : Screen()

    /** 本地音乐（MediaStore 媒体库）。 */
    @Serializable data object Local : Screen()

    /** 收藏的歌曲。 */
    @Serializable data object Favorites : Screen()

    /** 缓存的音乐（设置 → 音乐库）。 */
    @Serializable data object CachedMusic : Screen()

    /** 网盘音乐（主音源）。 */
    @Serializable data object CloudMusic : Screen()

    /** 在线集合详情（kind = playlist | album | artist）。 */
    @Serializable
    data class OnlineDetail(
        val kind: String,
        val sourceId: String,
        val id: String,
        val title: String
    ) : Screen()

    /** 全屏播放页（竖向滑入）。 */
    @Serializable data object Player : Screen()
}
