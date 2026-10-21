package com.xmgo.music.data.datastore

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xmgo.music.utils.XmgoPaletteStyle
import com.xmgo.music.utils.XmgoTheme

private const val PREFERENCES_NAME = "xmaee_settings"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = PREFERENCES_NAME
)

data object PreferencesKeys {
    val THEME = stringPreferencesKey("theme")
    val PALETTE_STYLE = stringPreferencesKey("palette_style")
    val USE_SYSTEM_FONT = booleanPreferencesKey("use_system_font")
    val MONET_COVER = booleanPreferencesKey("monet_cover")
    val PRIMARY_SOURCE = stringPreferencesKey("primary_source")
    val PLUGIN_SETUP_ACK = booleanPreferencesKey("plugin_setup_ack")
    // 悬浮歌词（与 OverlayLyricManager.KEY_* 保持一致）
    val OVERLAY_LYRIC_ENABLED = booleanPreferencesKey("overlay_lyric_enabled")
    val OVERLAY_LYRIC_LOCKED = booleanPreferencesKey("overlay_lyric_locked")
    val OVERLAY_LYRIC_FONT_SIZE = intPreferencesKey("overlay_lyric_font_size")
    val OVERLAY_LYRIC_WIDTH = intPreferencesKey("overlay_lyric_width")
    val OVERLAY_LYRIC_BG_OPACITY = intPreferencesKey("overlay_lyric_bg_opacity")
    val OVERLAY_LYRIC_OUTLINE = booleanPreferencesKey("overlay_lyric_outline")
}

@Composable
fun rememberAppTheme() =
    rememberPreference(key = PreferencesKeys.THEME, defaultValue = XmgoTheme.SYSTEM)

@Composable
fun rememberPaletteStyle() =
    rememberPreference(key = PreferencesKeys.PALETTE_STYLE, defaultValue = XmgoPaletteStyle.MONET)

@Composable
fun rememberUseSystemFont() =
    rememberPreference(key = PreferencesKeys.USE_SYSTEM_FONT, defaultValue = false)

@Composable
fun rememberMonetCover() =
    rememberPreference(key = PreferencesKeys.MONET_COVER, defaultValue = true)

/** 主音源插件 id；空串 = 自动（第一个有 home 能力的插件）。优先识别并展示该音源内容。 */
@Composable
fun rememberPrimarySource() =
    rememberPreference(key = PreferencesKeys.PRIMARY_SOURCE, defaultValue = "")

/** 首启音源配置弹窗是否已处理（选「只用本地」或装好插件后不再弹，对齐 qplayer PluginSetupState）。 */
@Composable
fun rememberPluginSetupAck() =
    rememberPreference(key = PreferencesKeys.PLUGIN_SETUP_ACK, defaultValue = false)

// ---------------------------------------------------------------- 悬浮歌词

@Composable
fun rememberOverlayLyricEnabled() =
    rememberPreference(key = PreferencesKeys.OVERLAY_LYRIC_ENABLED, defaultValue = false)

@Composable
fun rememberOverlayLyricLocked() =
    rememberPreference(key = PreferencesKeys.OVERLAY_LYRIC_LOCKED, defaultValue = false)

@Composable
fun rememberOverlayLyricFontSize() =
    rememberPreference(key = PreferencesKeys.OVERLAY_LYRIC_FONT_SIZE, defaultValue = 16)

@Composable
fun rememberOverlayLyricWidth() =
    rememberPreference(key = PreferencesKeys.OVERLAY_LYRIC_WIDTH, defaultValue = 90)

@Composable
fun rememberOverlayLyricBgOpacity() =
    rememberPreference(key = PreferencesKeys.OVERLAY_LYRIC_BG_OPACITY, defaultValue = 63)

@Composable
fun rememberOverlayLyricOutline() =
    rememberPreference(key = PreferencesKeys.OVERLAY_LYRIC_OUTLINE, defaultValue = true)
