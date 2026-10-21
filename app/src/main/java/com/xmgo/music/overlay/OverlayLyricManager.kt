package com.xmgo.music.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.xmgo.music.data.datastore.PreferencesKeys
import com.xmgo.music.data.datastore.dataStore
import com.xmgo.music.lyrics.LyricLine
import com.xmgo.music.lyrics.LyricsRepository
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.presentation.theme.AppThemePalette
import com.xmgo.music.utils.XmgoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 悬浮歌词管理器：读设置、申请悬浮窗权限、每 100ms 把播放状态推给
 * [OverlayLyricWindow]（对齐 qplayer PlaybackService 的 checkpoint tick）。
 * 不做前台隐藏——对齐 qplayer：开关打开且有权限即显示。
 */
class OverlayLyricManager(
    private val context: Context,
    private val playerController: PlayerController,
    private val lyricsRepository: LyricsRepository
) {
    companion object {
        val KEY_ENABLED = booleanPreferencesKey("overlay_lyric_enabled")
        val KEY_LOCKED = booleanPreferencesKey("overlay_lyric_locked")
        val KEY_FONT_SIZE = intPreferencesKey("overlay_lyric_font_size")
        val KEY_WIDTH = intPreferencesKey("overlay_lyric_width")
        val KEY_BG_OPACITY = intPreferencesKey("overlay_lyric_bg_opacity")
        val KEY_OUTLINE = booleanPreferencesKey("overlay_lyric_outline")

        fun hasOverlayPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

        fun openOverlayPermissionSettings(context: Context) {
            if (Build.VERSION.SDK_INT < 23) return
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickJob: Job? = null

    /** 开关状态（DataStore collect 到的 enabled）。 */
    @Volatile
    private var enabledState = false

    @Volatile
    private var currentStyle: OverlayLyricWindow.OverlayStyle? = null

    /** 主题色引用（Materialkolor scheme 对象，封面取色时会换新实例）。 */
    @Volatile
    private var lastSchemeRef: Any? = null

    @Volatile
    private var cachedLines: List<LyricLine> = emptyList()

    @Volatile
    private var cachedMediaId: String? = null

    init {
        // 设置变化 → 开关/重刷悬浮窗
        scope.launch {
            context.dataStore.data
                .map { prefs ->
                    Triple(
                        prefs[KEY_ENABLED] ?: false,
                        readStyle(prefs),
                        prefs[KEY_LOCKED] ?: false
                    )
                }
                .distinctUntilChanged()
                .collect { (enabled, style, locked) ->
                    apply(enabled, style.copy(locked = locked))
                }
        }
        // 切歌时先清空旧歌词（避免上一首残留），再预载
        scope.launch {
            var lastId: String? = null
            playerController.ui.collect { st ->
                val id = st.current?.mediaId
                if (id != null && id != lastId) {
                    lastId = id
                    cachedMediaId = id
                    cachedLines = emptyList()
                    val meta = st.current?.mediaMetadata
                    cachedLines = runCatching {
                        lyricsRepository.load(
                            id,
                            title = meta?.title?.toString(),
                            artist = meta?.artist?.toString()
                        )
                    }.getOrDefault(emptyList())
                }
            }
        }
    }

    private fun readStyle(prefs: androidx.datastore.preferences.core.Preferences): OverlayLyricWindow.OverlayStyle {
        val systemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = prefs[PreferencesKeys.THEME] ?: XmgoTheme.SYSTEM
        return OverlayLyricWindow.OverlayStyle(
            fontPx = prefs[KEY_FONT_SIZE] ?: 16,
            widthPct = prefs[KEY_WIDTH] ?: 90,
            bgOpacity = (prefs[KEY_BG_OPACITY] ?: 63) / 100f,
            dark = XmgoTheme.isDark(theme, systemDark),
            locked = prefs[KEY_LOCKED] ?: false,
            outline = prefs[KEY_OUTLINE] ?: true
        )
    }

    private fun apply(enabled: Boolean, style: OverlayLyricWindow.OverlayStyle) {
        currentStyle = style
        OverlayLyricWindow.restyle(style)
        if (!enabled) {
            enabledState = false
            OverlayLyricWindow.setWanted(context, false)
            stopTick()
            return
        }
        if (!hasOverlayPermission(context)) {
            // 用户从设置页打开总开关：跳系统授权页，回来后 DataStore 再 collect 时重挂
            openOverlayPermissionSettings(context)
            return
        }
        enabledState = true
        OverlayLyricWindow.attach(context)
        OverlayLyricWindow.setWanted(context, true)
        startTick()
    }

    private fun startTick() {
        if (tickJob?.isActive == true) return
        lastSchemeRef = AppThemePalette.scheme
        tickJob = scope.launch {
            while (true) {
                val st = playerController.ui.value
                // 主题色变化（如封面取色）→ 重刷窗口调色板
                val scheme = AppThemePalette.scheme
                if (scheme !== lastSchemeRef) {
                    lastSchemeRef = scheme
                    currentStyle?.let { OverlayLyricWindow.restyle(it) }
                }
                OverlayLyricWindow.tick(
                    OverlayLyricWindow.Frame(
                        playing = st.isPlaying,
                        title = st.current?.mediaMetadata?.title?.toString().orEmpty(),
                        artist = st.current?.mediaMetadata?.artist?.toString().orEmpty(),
                        lines = cachedLines,
                        positionMs = playerController.livePositionMs(),
                        receivedAtNs = System.nanoTime()
                    )
                )
                delay(100)
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    suspend fun setLocked(on: Boolean) {
        context.dataStore.edit { it[KEY_LOCKED] = on }
    }
}
