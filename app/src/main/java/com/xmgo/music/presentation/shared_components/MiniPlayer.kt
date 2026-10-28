package com.xmgo.music.presentation.shared_components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.data.datastore.toSongOrNull
import com.xmgo.music.playback.PlayerController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 列表底部为悬浮 MiniPlayer 预留的空间（contentPadding / 空态居中用）。
 * 此前 9 处硬编码 `108.dp` 散落各屏，与 MiniPlayer 实际高度同源维护于此。
 */
val MiniPlayerBottomReserve = 108.dp

/**
 * 悬浮胶囊迷你播放模块（仅首页与音乐列表页显示）：
 * 圆角悬浮 + 阴影，整个模块横向即播放进度（已播部分着色 + 底部进度线）。
 * 点击开全屏播放页。
 */
@Composable
fun MiniPlayer(
    playerController: PlayerController,
    onOpenPlayer: () -> Unit,
    enabled: Boolean = true
) {
    val state by playerController.ui.collectAsState()

    AnimatedVisibility(
        visible = enabled && state.current != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val shape = RoundedCornerShape(28.dp)
        val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        val lineColor = MaterialTheme.colorScheme.primary
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .drawBehind {
                        // 进度在绘制阶段读状态：进度变化仅重绘进度线，不触发 MiniPlayer 重组
                        val pos = playerController.positionMs.value
                        val fraction = if (state.durationMs > 0) {
                            (pos.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        } else 0f
                        if (fraction > 0f) {
                            drawRect(
                                color = tint,
                                size = size.copy(width = size.width * fraction)
                            )
                            val linePx = 3.dp.toPx()
                            drawRect(
                                color = lineColor,
                                topLeft = Offset(0f, size.height - linePx),
                                size = Size(size.width * fraction, linePx)
                            )
                        }
                    }
            ) {
                // 内容区滑动（换卡带隐喻）：封面+曲目信息跟手滑出滑入，条壳与播放按钮不动
                val swipeAnim = remember { Animatable(0f) }
                val animScope = rememberCoroutineScope()
                val density = LocalDensity.current
                val maxDragPx = with(density) { 110.dp.toPx() }
                // 收藏态（播放按钮右侧轻量图标）
                val favoritesStore: FavoritesStore = getKoin().get()
                val favorites by favoritesStore.favorites.collectAsState(initial = emptyList())
                val currentSong = state.current?.toSongOrNull()
                val isFavorite = currentSong?.let { s ->
                    favorites.any { it.sourceId == s.sourceId && it.rawId == s.rawId }
                } ?: false
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            val threshold = 80.dp.toPx()
                            val flingOut = 520.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDragEnd = {
                                    when {
                                        totalDrag <= -threshold -> animScope.launch {
                                            swipeAnim.animateTo(-flingOut, tween(160))
                                            playerController.next()
                                            swipeAnim.snapTo(flingOut)
                                            swipeAnim.animateTo(0f, tween(220))
                                        }
                                        totalDrag >= threshold -> animScope.launch {
                                            swipeAnim.animateTo(flingOut, tween(160))
                                            playerController.previous()
                                            swipeAnim.snapTo(-flingOut)
                                            swipeAnim.animateTo(0f, tween(220))
                                        }
                                        else -> animScope.launch {
                                            swipeAnim.animateTo(
                                                0f,
                                                spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                                            )
                                        }
                                    }
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    totalDrag += dragAmount
                                    animScope.launch {
                                        val cur = swipeAnim.value
                                        val target = (cur + dragAmount).coerceIn(-maxDragPx, maxDragPx)
                                        swipeAnim.snapTo(target)
                                    }
                                }
                            )
                        }
                        .clickable(onClick = onOpenPlayer)
                        .padding(horizontal = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .offset { IntOffset(swipeAnim.value.roundToInt(), 0) }
                            .graphicsLayer {
                                // 越往边缘越透明（边缘近乎全透明），渐隐过渡连续柔和
                                val frac = (abs(swipeAnim.value) / maxDragPx).coerceIn(0f, 1f)
                                alpha = 1f - frac * 0.85f
                            }
                    ) {
                    val artwork = state.current?.mediaMetadata?.artworkUri
                    if (artwork != null) {
                        AsyncImage(
                            model = artwork,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.current?.mediaMetadata?.title?.toString() ?: "未知歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = state.current?.mediaMetadata?.artist?.toString() ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    }

                    // 切歌走左右滑手势（左滑下一首/右滑上一首），按钮位省给文字区
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clickable(onClick = playerController::togglePlayPause)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            val s = state.current?.toSongOrNull() ?: return@IconButton
                            animScope.launch {
                                withContext(NonCancellable) {
                                    favoritesStore.setFavorite(s, !isFavorite)
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isFavorite) "取消收藏" else "收藏",
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
