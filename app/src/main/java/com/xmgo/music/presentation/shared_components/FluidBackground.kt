package com.xmgo.music.presentation.shared_components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xmgo.music.data.datastore.rememberAppTheme
import com.xmgo.music.utils.XmgoTheme

/**
 * 播放页背景：有封面时铺满模糊封面 + 主题自适应压暗 scrim；
 * 无封面回退主题色纵向渐变（顶部主色 → 底部纯黑）。
 */
@Composable
fun FluidBackground(
    artworkUri: Uri?,
    fallbackColors: List<Color>,
    modifier: Modifier = Modifier
) {
    val theme by rememberAppTheme()
    val systemDark = isSystemInDarkTheme()
    val appDark = remember(theme, systemDark) { XmgoTheme.isDark(theme, systemDark) }
    // 浅色主题下 scrim 稍浅（保留一点封面感），深色主题压更暗保证白字可读
    val scrimTop = if (appDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.40f)
    val scrimBottom = if (appDark) Color.Black.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.72f)

    Box(modifier = modifier.fillMaxSize()) {
        // 无封面时的回退渐变（始终绘制，封面模糊层不透明覆盖其上）
        val top = fallbackColors.getOrElse(0) { Color(0xFF3D5A80) }.copy(alpha = 1f)
        val mid = fallbackColors.getOrElse(1) { top }.copy(alpha = 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(top, mid, Color.Black),
                        startY = 0f,
                        endY = 2992f
                    )
                )
        )

        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(48.dp)
            )
        }

        // 主题自适应压暗层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(scrimTop, scrimBottom, Color.Black)
                    )
                )
        )
    }
}
