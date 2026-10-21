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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
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
        val layoutInfo = listState.layoutInfo
        val viewportCenterPx = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val halfViewportPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2f

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = halfViewport),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lines, key = { i, line -> "${line.timeMs}_$i" }) { index, line ->
                val isCurrent = index == currentIndex
                val itemInfo = layoutInfo.visibleItemsInfo.find { it.index == index }
                val lineCenter = itemInfo?.let { it.offset + it.size / 2f } ?: viewportCenterPx
                val distFraction = if (halfViewportPx > 0f) {
                    (abs(lineCenter - viewportCenterPx) / halfViewportPx).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val blurRadius = (distFraction * 6f).dp
                val lineAlpha = if (isCurrent) 1f else (1f - distFraction * 0.55f)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (blurRadius > 0.01.dp) {
                                Modifier.blur(blurRadius, BlurredEdgeTreatment.Rectangle)
                            } else {
                                Modifier
                            }
                        )
                        .graphicsLayer { alpha = lineAlpha }
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
