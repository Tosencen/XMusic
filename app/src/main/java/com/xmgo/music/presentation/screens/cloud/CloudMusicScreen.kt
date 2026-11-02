package com.xmgo.music.presentation.screens.cloud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.xmgo.music.playback.CloudScanManager
import com.xmgo.music.playback.CloudSourceManager
import com.xmgo.music.playback.CloudStream
import com.xmgo.music.playback.CloudTrack
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.playback.toMediaItem
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 网盘音乐列表页（主音源的音频文件）：
 * 扫描主音源根目录 → 列表 → 点击/播放全部 → staged（取直链→播放器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudMusicScreen(
    onNavigateUp: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by CloudScanManager.result.collectAsState()
    val scanning by CloudScanManager.scanning.collectAsState()
    val primary by CloudSourceManager.primary
    val playerController: PlayerController = getKoin().get()
    val playerState by playerController.ui.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 定位到正在播放：注意列表前面还有 folders.size 个文件夹项，
    // 歌曲在 LazyColumn 里的 index = 文件夹数 + 它在 tracks 里的下标。
    val tracksInList = result?.tracks.orEmpty()
    val foldersInList = result?.folders.orEmpty()
    val currentMediaId = playerState.current?.mediaId
    val currentIndex = remember(tracksInList, currentMediaId) {
        val i = tracksInList.indexOfFirst { CloudStream.mediaIdOf(it) == currentMediaId }
        if (i < 0) -1 else foldersInList.size + i
    }
    var locatePulse by remember { mutableStateOf(false) }

    /**
     * 起播：网盘直链是懒解析（见 [com.xmgo.music.playback.CloudResolvingDataSource]），
     * 构造队列不需要网络，所以整份队列一次交给播放器，点哪首就从哪首起播。
     *
     * 只把 [CloudScanManager.PLAYABLE_EXT] 里的歌交给播放器：WMA/APE/DSD 等
     * ExoPlayer 没有解封装器，送进去只会在播放器里报 Source error 而且**毫无提示**。
     */
    fun playFrom(tracks: List<CloudTrack>, index: Int) {
        if (tracks.isEmpty()) return
        val start = tracks.getOrNull(index)
        if (start != null && !CloudScanManager.isPlayable(start.name)) {
            val ext = start.name.substringAfterLast('.', "").uppercase()
            Toast.makeText(context, "「${start.name.substringBeforeLast('.')}」是 $ext 格式，暂不支持播放", Toast.LENGTH_LONG).show()
            return
        }
        val playable = tracks.filter { CloudScanManager.isPlayable(it.name) }
        if (playable.isEmpty()) {
            Toast.makeText(context, "该目录下的音频格式暂不支持播放", Toast.LENGTH_LONG).show()
            return
        }
        if (playable.size < tracks.size) {
            Toast.makeText(context, "已跳过 ${tracks.size - playable.size} 首暂不支持的格式", Toast.LENGTH_SHORT).show()
        }
        playerController.playQueue(playable.map { it.toMediaItem() }, startIndex = playable.indexOf(start))
    }

    // 进入页面自动扫描（无结果时）
    LaunchedEffect(Unit) {
        if (result == null && !scanning) CloudScanManager.scanRoot()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("${primary?.displayName ?: "网盘"}音乐", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { CloudScanManager.scanRoot() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 164.dp)
            ) {
                com.xmgo.music.presentation.shared_components.ScrollToCurrentButton(
                    listState = listState,
                    currentIndex = currentIndex,
                    onLocated = {
                        scope.launch {
                            locatePulse = true
                            kotlinx.coroutines.delay(1200)
                            locatePulse = false
                        }
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                com.xmgo.music.presentation.shared_components.ScrollToTopButton(
                    listState = listState
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when {
                scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                result == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无网盘音乐（右上角刷新扫描）",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    val r = result!!
                    val tracks = r.tracks
                    val folders = r.folders
                    // 播放全部 + 面包屑（下钻位置）。
                    // 根目录下常常只有子文件夹、没有散落的音频文件，此时不该给「播放全部 (0)」。
                    if (tracks.isNotEmpty() || !r.isRoot) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (tracks.isNotEmpty()) {
                                FilledTonalButton(onClick = { playFrom(tracks, 0) }) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("播放全部 (${tracks.size})")
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (!r.isRoot) {
                                TextButton(onClick = { scope.launch { CloudScanManager.scanRoot() } }) {
                                    Text("↑ 返回根目录", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    if (tracks.isEmpty() && folders.isEmpty()) {
                        // 空目录：给出可读提示（而不是一片空白）
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Rounded.FolderOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "「${r.folderName}」里没有找到音频文件",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "可识别 ${CloudScanManager.AUDIO_EXT.joinToString("、")}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "其中 DSD(dsf/dff) 暂不支持播放",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                    LazyColumn(
                        state = listState,
                        // 悬浮 MiniPlayer 会挡住列表底部，滚动到底时最后一项必须能完整露出
                        contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)
                    ) {
                        // 文件夹（下钻）
                        items(folders.size) { i ->
                            val folder = folders[i]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch { CloudScanManager.scanFolder(folder.fileId, folder.name) }
                                    }
                                    // 14dp padding + 40dp 图标容器 = 68dp 行高，
                                    // 与歌曲行、本地 SongRow 齐平（原先 46dp 明显偏矮）
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                // 图标套浅底容器：视觉锚点更强，一眼区分「文件夹」和「文件」；
                                // 裸图标放大到 28dp 会比 15sp 文字突出、比例失衡
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        )
                                ) {
                                    Icon(
                                        Icons.Rounded.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = folder.name,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // 音频文件
                        itemsIndexed(tracks) { index, track ->
                            CloudTrackRow(
                                track = track,
                                index = index,
                                isCurrent = CloudStream.mediaIdOf(track) == currentMediaId,
                                highlightPulse = locatePulse,
                                onClick = { playFrom(tracks, index) }
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudTrackRow(
    track: CloudTrack,
    index: Int,
    isCurrent: Boolean = false,
    highlightPulse: Boolean = false,
    onClick: () -> Unit
) {
    // 正在播放行的底色：平时 0.45，被定位时闪两下提醒（与 SongRow 同一套）
    val currentBgAlpha = remember { Animatable(0.45f) }
    LaunchedEffect(highlightPulse) {
        if (highlightPulse && isCurrent) {
            repeat(2) {
                currentBgAlpha.animateTo(0.95f, tween(170))
                currentBgAlpha.animateTo(0.45f, tween(170))
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) {
                    Modifier
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer
                                .copy(alpha = currentBgAlpha.value)
                        )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            // 10dp padding + 约 48dp 内容（标题+大小+来源 chip）= 68dp 行距，
            // 与本地 SongRow、文件夹行齐平。**不要动这个值**：
            // 实测加到 14dp 会让行距变成 76dp，反而偏离 SongRow 的 68dp
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = (index + 1).toString().padStart(2, '0'),
            fontSize = 13.sp,
            color = if (isCurrent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(32.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = track.name.substringBeforeLast('.'),
                fontSize = 15.sp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = formatSize(track.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 来源 chip
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        ) {
            Text(
                text = track.sourceType.displayName.take(2),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes > 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes > 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
