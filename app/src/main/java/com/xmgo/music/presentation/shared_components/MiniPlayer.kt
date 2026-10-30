package com.xmgo.music.presentation.shared_components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xmgo.music.R
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.data.datastore.toSongOrNull
import com.xmgo.music.playback.CloudStream
import com.xmgo.music.playback.PlayerController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin
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
                        // 进度在绘制阶段读状态：进度变化仅重绘进度 tint，不触发 MiniPlayer 重组
                        val pos = playerController.positionMs.value
                        val fraction = if (state.durationMs > 0) {
                            (pos.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        } else 0f
                        if (fraction > 0f) {
                            drawRect(
                                color = tint,
                                size = size.copy(width = size.width * fraction)
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
                    val mediaId = state.current?.mediaId
                    // 网盘 / 本地统一用应用自带默认封面：
                    // 云盘根本不给缩略图（artworkUri 为空），本地内嵌专辑图质量参差、
                    // 有的有图有的没图。与其在「网络图 / 空占位」之间来回跳变，
                    // 不如固定一张，视觉稳定、也不用为拿不到封面去做网络/解码开销。
                    val useDefaultCover =
                        CloudStream.isCloudMediaId(mediaId) || (mediaId?.startsWith("local:") == true)
                    val artwork = state.current?.mediaMetadata?.artworkUri?.takeUnless { useDefaultCover }
                    if (useDefaultCover) {
                        Image(
                            painter = painterResource(R.drawable.default_cover),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else if (artwork != null) {
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

                // 底部满宽 EQ 频谱条：生长式显隐（0-8dp 从底部长出/缩回）、整体偏缓，
                // 贴圆角底边、被 clip 自然裁切两端
                EqBars(
                    isPlaying = state.isPlaying,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .height(10.dp)
                )
            }
        }
    }
}

/** 迷你播放器底部满宽 EQ 频谱条：每根条独立正弦律动，生长式显隐、整体偏缓。 */
@Composable
private fun EqBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // 生长系数 0→1：条高按比例长出/缩回（生长式出现/消失，非透明度渐变）
    val grow by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(420),
        label = "eqGrow"
    )
    val transition = rememberInfiniteTransition(label = "eq")
    // 连续时钟：所有条共用时间轴，但每根用自己的速度/相位 → 各蹦各的、不像波浪
    // 不用 by 委托：State 在 Canvas 绘制阶段读 → 只重绘不重组（防卡顿）
    val phaseState = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "eqPhase"
    )
    // 每根条的确定性随机参数（seed 固定=形态稳定不跳变）
    // 速度取整数倍（1/2/3）：phase 2π→0 循环点 sin(n·2π+φ)=sin(φ) 无缝——
    // 非整数速度在循环点形态突变=每 1.8s"重置"一下的观感
    val barParams = remember {
        List(32) { i ->
            val r = java.util.Random(i * 7919L + 31)
            floatArrayOf(
                r.nextFloat() * 2f * Math.PI.toFloat(),        // 相位
                (1 + r.nextInt(3)).toFloat(),                  // 速度（整数倍速）
                0.12f + r.nextFloat() * 0.88f                  // 基准高度权重
            )
        }
    }
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val phase = phaseState.value // 绘制阶段读：帧驱动重绘、不触发重组
        val bars = barParams.size
        val unit = size.width / (bars * 2.2f - 1.2f) // 条:隙 ≈ 1.5:2.5
        val barW = unit * 1.2f
        val minH = 0.dp.toPx()
        val maxH = 8.dp.toPx()
        val corner = 1.dp.toPx() // 小圆角=条状（全圆角会像胶囊/点）
        for (i in 0 until bars) {
            val p0 = barParams[i][0]
            val speed = barParams[i][1]
            val bias = barParams[i][2]
            // 条自身正弦 × 基准权重 × 生长系数 → 高度随 grow 长出/缩回
            val wave = (sin(phase * speed + p0) * 0.5f + 0.5f)
            val norm = (wave * 0.65f + bias * 0.35f) * grow
            val h = minH + (maxH - minH) * norm
            drawRoundRect(
                color = barColor.copy(alpha = 0.18f + 0.32f * norm),
                topLeft = Offset(i * (barW + unit), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(corner)
            )
        }
    }
}
