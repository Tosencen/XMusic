package com.xmgo.music.presentation.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
// import androidx.compose.material.icons.rounded.Speed // 倍速/睡眠定时暂时下线
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.data.datastore.rememberAppTheme
import com.xmgo.music.data.datastore.toSongOrNull
import com.xmgo.music.lyrics.LrcParser
import com.xmgo.music.lyrics.LyricLine
import com.xmgo.music.lyrics.LyricsRepository
import com.xmgo.music.playback.PlayerController
import kotlin.math.roundToInt
import com.xmgo.music.presentation.shared_components.FluidBackground
import com.xmgo.music.presentation.shared_components.stableKeys
import com.xmgo.music.utils.XmgoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 全屏播放页：流体取色背景 + 封面/歌词视图 + 进度与播控 + 队列面板。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerScreen(onNavigateUp: () -> Unit) {
    val playerController: PlayerController = getKoin().get()
    val lyricsRepository: LyricsRepository = getKoin().get()
    val state by playerController.ui.collectAsState()

    val favoritesStore: FavoritesStore = getKoin().get()
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val scope = rememberCoroutineScope()
    val favorites by favoritesStore.favorites.collectAsState(initial = emptyList())
    val cacheProgress by cachedMusicStore.progress.collectAsState()
    val cachedList by cachedMusicStore.cached.collectAsState()
    val currentSong = remember(state.current) { state.current?.toSongOrNull() }
    val isFavorite = currentSong != null && favorites.any {
        it.sourceId == currentSong.sourceId && it.rawId == currentSong.rawId
    }
    val currentMediaId = state.current?.mediaId
    val isCached = currentMediaId != null && cachedList.any {
        FavoritesStore.mediaIdOf(it.song) == currentMediaId
    }
    val isCaching = currentMediaId != null && cacheProgress.containsKey(currentMediaId)

    val context = LocalContext.current
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val theme by rememberAppTheme()
    val appDark = remember(theme, systemDark) { XmgoTheme.isDark(theme, systemDark) }

    // 本页始终是深色画面：强制浅色状态栏/导航栏图标，退出时恢复主题设置。
    // key 用 appDark：主题在本页停留期间变化时 effect 重启，onDispose 读到的是最新值
    DisposableEffect(appDark) {
        val controller = context.findActivity()?.window?.let { WindowInsetsControllerCompat(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = !appDark
            controller?.isAppearanceLightNavigationBars = !appDark
        }
    }

    val mediaId = state.current?.mediaId
    val mediaTitle = state.current?.mediaMetadata?.title?.toString()
    val mediaArtist = state.current?.mediaMetadata?.artist?.toString()
    var lyrics by remember(mediaId) { mutableStateOf(emptyList<LyricLine>()) }
    var lyricsLoading by remember(mediaId) { mutableStateOf(mediaId != null) }

    LaunchedEffect(mediaId) {
        if (mediaId == null) {
            lyrics = emptyList()
            lyricsLoading = false
            return@LaunchedEffect
        }
        lyricsLoading = true
        lyrics = lyricsRepository.load(mediaId, title = mediaTitle, artist = mediaArtist)
        lyricsLoading = false
    }

    // 歌词索引在 LyricsPane 内部收集 positionMs 计算（见下方子组合），
    // 进度条同理抽为 PlayerProgressBar——两者各自收集高频进度流，
    // 播放页主体（封面/歌名/菜单/播控）不再随 500ms 进度重组

    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    // var showPlaybackSettings by remember { mutableStateOf(false) } // 倍速/睡眠定时暂时下线

    BackHandler(onBack = {
        when {
            showQueue -> showQueue = false
            showLyrics -> showLyrics = false
            else -> onNavigateUp()
        }
    })

    val fallbackColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary
    )
    // 视觉底部弹窗：顶部让出竖向 10%（弹窗 = 竖向 90%，间距含状态栏余量），
    // 内容区圆角上浮，画布只画内容区 → 圆角边缘即弹窗分界
    val topGap = androidx.compose.ui.platform.LocalConfiguration.current.let {
        androidx.compose.ui.unit.Dp(it.screenHeightDp * 0.10f)
    }
    // 把手拖拽跟手位移（下滑关闭：过阈值退出，否则弹簧回位）
    val sheetDrag = remember { androidx.compose.animation.core.Animatable(0f) }
    // scrim 在 Nav 层独立淡入淡出（不跟弹窗滑动）——拖拽进度写共享状态供其衰减透明度
    androidx.compose.runtime.LaunchedEffect(sheetDrag.value) {
        com.xmgo.music.playback.PlayerSheetState.dragProgress.floatValue =
            (sheetDrag.value / 330f).coerceIn(0f, 1f)
    }
    FluidBackground(
        artworkUri = state.current?.mediaMetadata?.artworkUri,
        fallbackColors = fallbackColors,
        modifier = Modifier
            .fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(0, sheetDrag.value.roundToInt()) }
            .padding(top = topGap)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
    )

    // 封面从屏幕顶端铺起；返回按钮悬浮于封面左上
    Column(
        modifier = Modifier
            .fillMaxSize()
            .offset { androidx.compose.ui.unit.IntOffset(0, sheetDrag.value.roundToInt()) }
            .padding(top = topGap)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
    ) {

        // 中部：封面（铺满置顶）或 歌词
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val boxMaxHeight = maxHeight
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "playerCenter"
            ) { asLyrics ->
                if (!asLyrics) {
                    // 封面+歌名整组在中部居中，歌名紧跟封面，底部留出按钮行的高度
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.weight(1f))
                        // 封面：正方形卡片。原整幅铺满会把低分辨率封面放大到发糊，
                        // 缩成正方形后展示面积更小、观感更锐利；点击区域仍是整块进入歌词
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .fillMaxWidth(0.78f)
                                .aspectRatio(1f)
                                .shadow(24.dp, RoundedCornerShape(20.dp))
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                                .clickable { showLyrics = true },
                            contentAlignment = Alignment.Center
                        ) {
                            val artwork = state.current?.mediaMetadata?.artworkUri
                            if (artwork != null) {
                                AsyncImage(
                                    model = artwork,
                                    contentDescription = "封面",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(72.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = state.current?.mediaMetadata?.title?.toString() ?: "未在播放",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.current?.mediaMetadata?.artist?.toString() ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        // 底部弹性空间稍大，保证歌名/歌手不压到左下歌词、右下收起按钮
                        Spacer(modifier = Modifier.weight(1.15f))
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 歌词模式：整屏歌词（顶部不显示歌名）。
                        // 左下角封面切换/右下角收起按钮在 AnimatedContent 外常驻，见下方
                        LyricsPane(
                            lyrics = lyrics,
                            loading = lyricsLoading,
                            halfViewport = boxMaxHeight / 2,
                            onSeek = { position -> playerController.seekTo(position) },
                            playerController = playerController,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            // 「词/图」切换：与右下角收起按钮同一水平线、位于封面/歌词下方左侧（一左一右）。
            // 封面态显示歌词图标进歌词（点封面也可进）；歌词态显示封面图标切回
            IconButton(
                onClick = { showLyrics = !showLyrics },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                Icon(
                    imageVector = if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics,
                    contentDescription = if (showLyrics) "显示封面" else "显示歌词",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            // 收起按钮：中心区右下角（与左下歌词/封面切换同一水平线），向下箭头收起整个播放页
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "收起播放页",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            // 顶部 scrim 区点击关闭：负偏移盖住弹窗上方的 scrim 带
            // （本 Box 的父顶=弹窗顶，不能正向 padding topGap——会双重偏移）
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .offset(y = -topGap)
                    .height(topGap)
                    .clickable(onClick = onNavigateUp)
            )
            // 下滑把手：命中区大于横条（好按），拖动整个弹窗跟手下移，
            // 过阈值关闭、不足弹簧回位（与下滑关闭手势同语义）
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .width(64.dp)
                    .height(32.dp)
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        val threshold = 120.dp.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onDragEnd = {
                                if (totalDrag > threshold) {
                                    // 弹层 exit（slideOut+fadeOut）接管滑出观感
                                    onNavigateUp()
                                } else {
                                    scope.launch {
                                        sheetDrag.animateTo(
                                            0f,
                                            androidx.compose.animation.core.spring()
                                        )
                                    }
                                }
                            },
                            onVerticalDrag = { _, dragAmount ->
                                totalDrag += dragAmount
                                if (dragAmount > 0f) {
                                    scope.launch {
                                        sheetDrag.snapTo(
                                            (sheetDrag.value + dragAmount).coerceAtLeast(0f)
                                        )
                                    }
                                }
                            }
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.8f))
                )
            }
        }

        // 进度条：波浪样式 + 透明 Slider 保留拖动（子组合内收集高频进度流）
        PlayerProgressBar(
            durationMs = state.durationMs,
            playerController = playerController
        )

        // 播控
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = playerController::toggleShuffle) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "随机播放",
                    tint = if (state.shuffleEnabled) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
            IconButton(onClick = playerController::previous) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
            FilledIconButton(
                onClick = playerController::togglePlayPause,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black
                )
            ) {
                if (state.buffering && !state.isPlaying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = Color.Black,
                        strokeWidth = 3.dp
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            IconButton(onClick = playerController::next) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
            IconButton(onClick = {
                // 列表/随机已在左侧切换；此处仅在 顺序 与 单曲循环 间切换
                val next = when (state.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE ->
                        androidx.media3.common.Player.REPEAT_MODE_OFF
                    else -> androidx.media3.common.Player.REPEAT_MODE_ONE
                }
                playerController.setRepeatMode(next)
            }) {
                val repeatOn = state.repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE
                Icon(
                    imageVector = if (repeatOn) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = if (repeatOn) "单曲循环，点击切回顺序播放" else "顺序播放，点击切换单曲循环",
                    tint = if (repeatOn) Color.White else Color.White.copy(alpha = 0.5f)
                )
            }
        }

        // 下载 + 收藏 + 队列：播控行下方（居中）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 2.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentSong != null) {
                IconButton(onClick = {
                    val mediaId = currentMediaId ?: return@IconButton
                    if (!isCached && !isCaching) {
                        cachedMusicStore.cacheAsync(currentSong)
                    }
                }) {
                    when {
                        isCaching -> {
                            val pct = cacheProgress[currentMediaId]?.fraction ?: -1f
                            if (pct >= 0f) {
                                CircularProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.3f),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                        isCached -> Icon(
                            imageVector = Icons.Rounded.DownloadDone,
                            contentDescription = "已缓存",
                            tint = Color.White
                        )
                        else -> Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "缓存该音乐",
                            tint = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
                IconButton(onClick = {
                    val song = currentSong
                    scope.launch { favoritesStore.setFavorite(song, !isFavorite) }
                }) {
                    Icon(
                        imageVector = if (isFavorite) {
                            Icons.Rounded.Favorite
                        } else {
                            Icons.Rounded.FavoriteBorder
                        },
                        contentDescription = "收藏",
                        tint = if (isFavorite) Color.White else Color.White.copy(alpha = 0.75f)
                    )
                }
            }
            // 倍速/睡眠定时功能暂时注释（2026-10-16，按需求下线，代码保留可恢复）
            // IconButton(onClick = { showPlaybackSettings = true }) {
            //     Icon(
            //         imageVector = Icons.Rounded.Speed,
            //         contentDescription = "播放设置",
            //         tint = if (playerController.sleepRemainingMs.value > 0) {
            //             MaterialTheme.colorScheme.primary
            //         } else {
            //             Color.White.copy(alpha = 0.75f)
            //         }
            //     )
            // }
            IconButton(onClick = { showQueue = true }) {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = "播放队列",
                    tint = Color.White
                )
            }
        }
    }

    if (showQueue) {
        QueueSheet(
            queue = state.queue,
            currentMediaId = state.current?.mediaId,
            shuffleEnabled = state.shuffleEnabled,
            onToggleShuffle = { playerController.toggleShuffle() },
            onSelect = { index ->
                playerController.seekToIndex(index)
                showQueue = false
            },
            onDismiss = { showQueue = false }
        )
    }

    // 倍速/睡眠定时暂时下线
    // if (showPlaybackSettings) {
    //     PlaybackSettingsSheet(
    //         playerController = playerController,
    //         onDismiss = { showPlaybackSettings = false }
    //     )
    // }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(
    queue: List<MediaItem>,
    currentMediaId: String?,
    shuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(540.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "播放队列", style = MaterialTheme.typography.titleMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "${queue.size} 首",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    com.xmgo.music.presentation.shared_components.PlayModeToggleIcon(
                        shuffleEnabled = shuffleEnabled,
                        onToggle = onToggleShuffle
                    )
                }
            }
            // 队列 key 预计算：混 index 会让随机播放/增删时整列 key 全变（全量重绑）；
            // 去重计数既保证同一首歌重复入队时 key 唯一，又让未变动项保留组合状态
            val queueKeys = remember(queue) { stableKeys(queue) { it.mediaId } }
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(queue, key = { index, _ -> queueKeys[index] }) { index, item ->
                    val isCurrent = item.mediaId == currentMediaId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.mediaMetadata.title?.toString() ?: "未知歌曲",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.mediaMetadata.artist?.toString() ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 播放进度条（波浪 + 透明 Slider 拖动）。
 * 独立收集高频 [PlayerController.positionMs] 流：进度每 500ms 变化时
 * 只有本组合重组，播放页主体（封面/歌名/播控）不再整体重组。
 */
@Composable
private fun PlayerProgressBar(
    durationMs: Long,
    playerController: PlayerController
) {
    val positionMs by playerController.positionMs.collectAsState()
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayPosition = if (scrubbing) scrubPosition.toLong() else positionMs

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Box(contentAlignment = Alignment.Center) {
            LinearWavyProgressIndicator(
                progress = {
                    if (durationMs > 0) {
                        displayPosition.toFloat() / durationMs.toFloat()
                    } else {
                        0f
                    }
                },
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f),
                amplitude = { 0.22f },
                wavelength = 36.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
            )
            Slider(
                value = displayPosition.toFloat(),
                onValueChange = { value ->
                    scrubbing = true
                    scrubPosition = value
                },
                onValueChangeFinished = {
                    playerController.seekTo(scrubPosition.toLong())
                    scrubbing = false
                },
                valueRange = 0f..(durationMs.coerceAtLeast(1)).toFloat(),
                enabled = durationMs > 0,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                thumb = { }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(displayPosition),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 歌词面板：独立高频 tick（120ms）读实时进度算当前行索引，
 * 只在切行时重组（positionMs 流 500ms 粒度会慢一句，不能用）。
 */
@Composable
private fun LyricsPane(
    lyrics: List<LyricLine>,
    loading: Boolean,
    halfViewport: androidx.compose.ui.unit.Dp,
    onSeek: (Long) -> Unit,
    playerController: PlayerController,
    modifier: Modifier = Modifier
) {
    var lyricIndex by remember(lyrics) {
        mutableIntStateOf(LrcParser.currentIndex(lyrics, playerController.livePositionMs()))
    }
    LaunchedEffect(lyrics) {
        while (isActive) {
            val idx = LrcParser.currentIndex(lyrics, playerController.livePositionMs())
            if (idx != lyricIndex) lyricIndex = idx
            delay(120)
        }
    }
    LyricsView(
        lines = lyrics,
        currentIndex = if (loading) -2 else lyricIndex,
        halfViewport = halfViewport,
        onSeek = onSeek,
        modifier = modifier
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
