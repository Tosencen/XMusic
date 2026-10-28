package com.xmgo.music.presentation.screens.player

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import com.xmgo.music.lyrics.LrcParser
import com.xmgo.music.lyrics.LyricLine
import kotlin.math.abs

/**
 * 歌词滚动视图：当前行居中高亮，点击任意行 seek。
 * @param halfViewport 用于 contentPadding，使首尾行也能滚到屏幕中央。
 */
@Composable
fun LyricsView(
    lines: List<LyricLine>,
    currentIndex: Int,
    halfViewport: androidx.compose.ui.unit.Dp,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.Lyrics,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = if (currentIndex == -2) "歌词加载中…" else "暂无歌词",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
        return
    }

    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect

        suspend fun centerOn(index: Int): Boolean {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.find { it.index == index } ?: return false
            val screenCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val itemCenter = item.offset + item.size / 2
            val delta = (itemCenter - screenCenter).toFloat()
            return if (abs(delta) >= 2f) {
                listState.animateScrollBy(delta)
                true
            } else {
                false
            }
        }

        if (!centerOn(currentIndex)) {
            listState.scrollToItem(currentIndex)
            withFrameNanos { } // 等布局更新后重新测量
            centerOn(currentIndex)
        }
    }

    Box(modifier = modifier) {
        // 预计算两级模糊 RenderEffect（量化半径档位，避免滚动时每帧新建模糊对象）。
        // graphicsLayer 的 renderEffect 在绘制阶段应用：滚动仅重绘，不触发整列重组。
        val density = androidx.compose.ui.platform.LocalDensity.current
        val blurEffects = remember(density) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                listOf(3f, 6f).map { dpValue ->
                    val px = with(density) { dpValue.dp.toPx() }
                    android.graphics.RenderEffect
                        .createBlurEffect(px, px, android.graphics.Shader.TileMode.CLAMP)
                        .asComposeRenderEffect()
                }
            } else {
                null
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = halfViewport),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lines, key = { _, line -> "${line.timeMs}_${line.text}" }) { index, line ->
                val isCurrent = index == currentIndex
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            // 绘制阶段读滚动布局（layoutInfo 每帧变化，组合期读取会导致
                            // 所有可见行每帧全部重组）：仅重绘，不重组
                            val info = listState.layoutInfo
                            val viewportCenter =
                                (info.viewportStartOffset + info.viewportEndOffset) / 2f
                            val half =
                                (info.viewportEndOffset - info.viewportStartOffset) / 2f
                            val item = info.visibleItemsInfo.firstOrNull { it.index == index }
                            val lineCenter =
                                item?.let { it.offset + it.size / 2f } ?: viewportCenter
                            val dist = if (half > 0f) {
                                (abs(lineCenter - viewportCenter) / half).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            alpha = if (isCurrent) 1f else (1f - dist * 0.55f)
                            // 距中心较远才模糊，两档量化：近处完全不走模糊管线
                            renderEffect = when {
                                dist < 0.3f -> null
                                dist < 0.65f -> blurEffects?.getOrNull(0)
                                else -> blurEffects?.getOrNull(1)
                            }
                        }
                        .clickable { onSeek(line.timeMs) }
                        .padding(horizontal = 32.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = line.text,
                        textAlign = TextAlign.Center,
                        fontSize = if (isCurrent) 22.sp else 18.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.55f)
                    )
                    line.translation?.let {
                        Text(
                            text = it,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = if (isCurrent) 0.7f else 0.35f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
