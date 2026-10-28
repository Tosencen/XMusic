package com.xmgo.music.presentation.screens.online

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.OnlineAlbum
import com.xmgo.music.plugin.model.OnlineCollection
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import com.xmgo.music.presentation.shared_components.PlayModeToggleIcon
import com.xmgo.music.presentation.shared_components.ResolvingIndicator
import com.xmgo.music.presentation.shared_components.SkeletonBlock
import com.xmgo.music.presentation.shared_components.SongActionsMenu
import com.xmgo.music.presentation.shared_components.SongRow
import com.xmgo.music.presentation.shared_components.rememberSkeletonPulse
import com.xmgo.music.presentation.shared_components.rememberStagedPlayer
import com.xmgo.music.presentation.shared_components.stableKeys
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 在线集合详情页：歌单 / 专辑 / 歌手（kind = playlist | album | artist）。
 * 头部元数据 + 播放全部 + 整批下载 + 歌曲列表；歌手附专辑横滑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineDetailScreen(
    kind: String,
    sourceId: String,
    id: String,
    onNavigateUp: () -> Unit,
    onNavigateToDetail: (kind: String, sourceId: String, id: String, title: String) -> Unit
) {
    val pluginManager: PluginManager = getKoin().get()
    val playerController: PlayerController = getKoin().get()
    val detailCache: OnlineDetailCache = getKoin().get()
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<OnlineCollection?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val playerState by playerController.ui.collectAsState()
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val staged = rememberStagedPlayer(pluginManager, playerController, cachedMusicStore)
    val cachedList by cachedMusicStore.cached.collectAsState()
    val batchProgress by cachedMusicStore.batchProgress.collectAsState()
    var actionsSong by remember { mutableStateOf<Song?>(null) }

    LaunchedEffect(sourceId, id, kind) {
        val cacheKey = OnlineDetailCache.keyOf(kind, sourceId, id)
        // 返回本页：命中缓存直接恢复，不再后台刷新（用户要求返回不自动刷新）
        detailCache.get(cacheKey)?.let { cached ->
            detail = cached
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val provider = pluginManager.providerFor(sourceId)
            val loaded = when (kind) {
                "album" -> provider.albumDetails(id)
                "artist" -> provider.artistDetails(id)
                else -> provider.playlistDetails(id)
            }
            detail = loaded
            detailCache.put(cacheKey, loaded)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (detail == null) error = e.message ?: e.toString()
        }
        loading = false
    }

    val listState = rememberLazyListState()
    // 头部高度（px）：滚动进度用它归一化，驱动顶栏标题渐显
    var headerHeightPx by remember { mutableIntStateOf(0) }
    // 0 = 头部完整可见（顶栏无标题），1 = 头部滚出（顶栏标题完全显示）
    val titleProgress by remember {
        derivedStateOf {
            when {
                listState.firstVisibleItemIndex > 0 -> 1f
                headerHeightPx <= 0 -> 0f
                else -> (listState.firstVisibleItemScrollOffset.toFloat() / headerHeightPx)
                    .coerceIn(0f, 1f)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                // 折叠标题：大标题在下方 CollectionHeader 展示，滚出视口时顶栏标题原位渐显，
                // 避免滚动后完全不知道当前在浏览哪个歌单
                title = {
                    detail?.let { d ->
                        Text(
                            text = d.name,
                            style = MaterialTheme.typography.titleLarge
                                .copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                alpha = titleProgress
                                // 轻微上滑入场，位移在绘制阶段计算，不触发重组
                                translationY = (1f - titleProgress) * 20.dp.toPx()
                            }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            com.xmgo.music.presentation.shared_components.ScrollToTopButton(
                listState = listState,
                modifier = Modifier.padding(bottom = 164.dp)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // 首次进入（无缓存）：骨架屏占位（呼吸脉冲），内容结构所见即所得
                loading && detail == null -> DetailSkeleton()

                error != null && detail == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = error ?: "加载失败",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                detail == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp)
                        )
                        Text(
                            text = "内容为空",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }

                else -> {
                    val d = detail!!
                    val myMediaIds = remember(d) {
                        d.songs.map { cachedMusicStore.mediaIdOf(it) }.toSet()
                    }
                    val cachedIds = remember(cachedList) {
                        cachedList.map { cachedMusicStore.mediaIdOf(it.song) }.toSet()
                    }
                    val allCached = remember(myMediaIds, cachedIds) {
                        myMediaIds.isNotEmpty() && myMediaIds.all { it in cachedIds }
                    }
                    // 歌曲 key 预计算：不混入 index，且重复曲目（#n 后缀）保证唯一
                    val songKeys = remember(d.songs) { stableKeys(d.songs) { it.id } }
                    val batch = batchProgress
                    val runningMine = batch != null && batch.mediaIds.any { it in myMediaIds }
                    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)) {
                        item {
                            CollectionHeader(
                                detail = d,
                                onPlayAll = { staged.playAt(d.songs, 0) },
                                shuffleEnabled = playerState.shuffleEnabled,
                                onToggleShuffle = { playerController.toggleShuffle() },
                                downloadFraction = if (runningMine && batch != null) batch.fraction else null,
                                downloadAllDone = allCached,
                                downloadEnabled = d.songs.isNotEmpty(),
                                onDownloadClick = {
                                    when {
                                        runningMine -> cachedMusicStore.cancelBatch()
                                        !allCached -> cachedMusicStore.cacheAll(d.songs)
                                    }
                                },
                                onHeightMeasured = { headerHeightPx = it }
                            )
                        }
                        if (d.songs.isEmpty()) {
                            item {
                                Text(
                                    text = "没有可播放的歌曲",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(20.dp)
                                )
                            }
                        } else {
                            itemsIndexed(
                                d.songs,
                                key = { index, _ -> songKeys[index] }
                            ) { index, song ->
                                val mediaId = "${song.sourceId}:${song.rawId}"
                                SongActionsMenu(
                                    song = song,
                                    expanded = actionsSong == song,
                                    onDismiss = { actionsSong = null }
                                ) {
                                    SongRow(
                                        title = song.name,
                                        artworkUrl = song.artworkUrl,
                                        subtitle = listOfNotNull(
                                            song.artists.joinToString(" / ").ifEmpty { null },
                                            song.album
                                        ).joinToString(" · "),
                                        durationMs = song.durationMs,
                                        isCurrent = playerState.current?.mediaId == mediaId,
                                        isPlaying = playerState.isPlaying &&
                                            playerState.current?.mediaId == mediaId,
                                        onLongClick = { actionsSong = song },
                                        onClick = { staged.playAt(d.songs, index) }
                                    )
                                }
                            }
                        }
                        if (d.albums.isNotEmpty()) {
                            item {
                                Text(
                                    text = "专辑",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(d.albums, key = { it.id }) { album ->
                                        AlbumCard(album = album) {
                                            onNavigateToDetail("album", sourceId, album.id, album.name)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }

            // 播放失败提示（加载失败走上方 detail==null 分支；这里覆盖内容已加载的场景）
            staged.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                )
            }

            ResolvingIndicator(staged.resolving)
        }
    }
}

@Composable
private fun CollectionHeader(
    detail: OnlineCollection,
    onPlayAll: () -> Unit,
    shuffleEnabled: Boolean,
    onToggleShuffle: () -> Unit,
    downloadFraction: Float? = null,
    downloadAllDone: Boolean = false,
    downloadEnabled: Boolean = true,
    onDownloadClick: () -> Unit = {},
    onHeightMeasured: (Int) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightMeasured(it.height) }
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (detail.artworkUrl != null) {
                    AsyncImage(
                        model = detail.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    when (detail.kind) {
                        "artist" -> "歌手"
                        "album" -> "专辑"
                        else -> "歌单"
                    },
                    detail.ownerName,
                    "${detail.songs.size} 首"
                ).joinToString(" · ")
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detail.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalButton(
                onClick = onPlayAll,
                enabled = detail.songs.isNotEmpty()
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("播放全部")
            }
            PlayModeToggleIcon(
                shuffleEnabled = shuffleEnabled,
                onToggle = onToggleShuffle,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
            DownloadPlaylistButton(
                progress = downloadFraction,
                allDone = downloadAllDone,
                enabled = downloadEnabled,
                onClick = onDownloadClick
            )
        }
    }
}

/**
 * 详情页骨架屏：布局与 CollectionHeader + 歌曲行一一对应（封面块 + 标题/元信息条 +
 * 按钮行 + N 行歌曲占位），呼吸脉冲透明度，加载期间内容结构所见即所得。
 */
@Composable
private fun DetailSkeleton() {
    val pulse = rememberSkeletonPulse()
    LazyColumn(contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)) {
        // 头部占位：封面 110dp + 右侧标题/元信息/简介条 + 按钮行
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    SkeletonBlock(
                        alpha = pulse,
                        modifier = Modifier.size(110.dp),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBlock(alpha = pulse, modifier = Modifier.fillMaxWidth(0.7f).height(22.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        SkeletonBlock(alpha = pulse, modifier = Modifier.fillMaxWidth(0.45f).height(14.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        SkeletonBlock(alpha = pulse, modifier = Modifier.fillMaxWidth().height(12.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SkeletonBlock(alpha = pulse, modifier = Modifier.height(40.dp).width(120.dp), shape = RoundedCornerShape(50))
                    SkeletonBlock(alpha = pulse, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(50))
                    SkeletonBlock(alpha = pulse, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(50))
                }
            }
        }
        // 歌曲行占位：40dp 封面 + 标题/副标题双条，尺寸与 SongRow 一致
        items(10) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                SkeletonBlock(alpha = pulse, modifier = Modifier.size(40.dp), shape = RoundedCornerShape(8.dp))
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(alpha = pulse, modifier = Modifier.fillMaxWidth(0.55f).height(16.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBlock(alpha = pulse, modifier = Modifier.fillMaxWidth(0.35f).height(12.dp))
                }
            }
        }
    }
}

/** 歌单下载按钮：空闲 ⬇ / 下载中进度圆环（点击取消）/ 全部已缓存 ✓。 */@Composable
private fun DownloadPlaylistButton(
    progress: Float?,
    allDone: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        when {
            progress != null && progress <= 0f -> CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "取消下载" },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )

            progress != null -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "取消下载" },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )

            allDone -> Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = "已全部缓存",
                tint = MaterialTheme.colorScheme.primary
            )

            else -> Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = "下载歌单",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AlbumCard(album: OnlineAlbum, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (album.artworkUrl != null) {
                AsyncImage(
                    model = album.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = album.artists.joinToString(" / "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
