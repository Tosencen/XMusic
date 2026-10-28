package com.xmgo.music.presentation.screens.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xmgo.music.data.datastore.rememberPrimarySource
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.AccountInfo
import com.xmgo.music.plugin.model.HomeContent
import com.xmgo.music.plugin.model.OnlinePlaylist
import com.xmgo.music.plugin.model.Song
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.xmgo.music.presentation.login.LoginDialog
import com.xmgo.music.presentation.shared_components.CircleBackdropWavyIndicator
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import com.xmgo.music.presentation.shared_components.ResolvingIndicator
import com.xmgo.music.presentation.shared_components.SongActionsMenu
import com.xmgo.music.presentation.shared_components.stableKeys
import com.xmgo.music.presentation.shared_components.SongRow
import com.xmgo.music.presentation.shared_components.rememberStagedPlayer
import java.util.Calendar
import org.koin.compose.getKoin

/**
 * 首页：右上角问候大标题 + 在线推荐内容（home handler：分组货架 / 推荐歌单 / 每日歌曲）。
 * 顶栏：左搜索、右收藏/设置；本地音乐等入口在 设置 → 音乐库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDetail: (kind: String, sourceId: String, id: String, title: String) -> Unit,
    playerController: PlayerController = getKoin().get(),
    pluginManager: PluginManager = getKoin().get()
) {
    val context = LocalContext.current

    // 响应式监听已装插件：首启后台下载完成后自动出现，无需重启
    val plugins by pluginManager.pluginsFlow.collectAsState()
    // 主音源优先（优先识别并展示该音源），其次首个声明 home 能力的插件
    val primarySource by rememberPrimarySource()
    val primary = remember(plugins, primarySource) {
        plugins.find { it.id == primarySource && pluginManager.hasCapability(it.id, "home") }
            ?: plugins.firstOrNull { pluginManager.hasCapability(it.id, "home") }
            ?: plugins.firstOrNull()
    }

    // 跨页面返回命中缓存则直接展示，避免每次都重新 home()
    val homeStore: HomeContentStore = getKoin().get()
    val cached = homeStore.cache.value

    var homeContent by remember { mutableStateOf<HomeContent?>(cached.content) }
    var loading by remember { mutableStateOf(cached.content == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showLogin by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<AccountInfo?>(cached.account) }
    // 当前展示内容所属音源：切主音源才清空旧内容，同音源下拉刷新保留旧内容原地换新
    var loadedSourceId by remember {
        mutableStateOf<String?>(if (cached.content != null) cached.primaryId else null)
    }
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val staged = rememberStagedPlayer(pluginManager, playerController, cachedMusicStore)
    var actionsSong by remember { mutableStateOf<Song?>(null) }

    val playerState by playerController.ui.collectAsState()

    LaunchedEffect(primary?.id, refreshTick) {
        val snapshot = homeStore.cache.value
        if (refreshTick == snapshot.tick &&
            primary?.id == snapshot.primaryId &&
            snapshot.content != null
        ) {
            homeContent = snapshot.content
            account = snapshot.account
            loadedSourceId = snapshot.primaryId
            loading = false
            error = null
            return@LaunchedEffect
        }
        if (primary == null) {
            loading = false
            homeContent = null
            return@LaunchedEffect
        }
        val sourceChanged = loadedSourceId != primary.id
        // 仅切主音源时清空旧音源内容（失败时展示错误而非残留旧内容）；
        // 同音源下拉刷新保留旧内容，加载完成原地替换
        if (sourceChanged) {
            homeContent = null
            account = null
        }
        loading = true
        error = null
        runCatching { pluginManager.providerFor(primary.id).home(limit = 12) }
            .onSuccess {
                homeContent = it
                loadedSourceId = primary.id
            }
            .onFailure {
                // 取消（页面离开/主音源切换重启）不当作加载失败展示
                if (it is kotlinx.coroutines.CancellationException) throw it
                if (sourceChanged || homeContent == null) {
                    error = it.message ?: it.toString()
                } else {
                    // 刷新失败：保留旧内容，顶部 Toast 提示
                    Toast
                        .makeText(context, "刷新失败：${it.message ?: it.toString()}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        loading = false
        account = runCatching { pluginManager.accountInfo(primary.id) }.getOrNull()
        // 仅在内容确实属于当前音源时写缓存，避免把加载失败的空/旧内容标记为该音源的新鲜缓存
        if (loadedSourceId == primary.id) {
            homeStore.cache.value = HomeCache(
                primaryId = primary.id,
                content = homeContent,
                account = account,
                tick = refreshTick
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = account?.takeIf { it.loggedIn }
                                ?.let { "${greeting()}，${it.displayName}" }
                                ?: greeting(),
                            style = MaterialTheme.typography.headlineSmall
                                .copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = primary?.name ?: "未安装音源插件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "搜索"
                        )
                    }
                    IconButton(onClick = onNavigateToFavorites) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = "收藏"
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "设置"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        val refreshState = rememberPullToRefreshState()
        var refreshing by remember { mutableStateOf(false) }
        LaunchedEffect(loading) {
            if (!loading) refreshing = false
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val content = homeContent
            PullToRefreshBox(
                isRefreshing = refreshing,
                state = refreshState,
                onRefresh = {
                    // 保留旧内容原地刷新，不走全屏 loading（内容跟手弹回后静默换新）
                    refreshing = true
                    loading = true
                    error = null
                    refreshTick++
                },
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    loading && content == null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircleBackdropWavyIndicator() }

                primary == null -> EmptyState(
                    title = "未安装音源插件",
                    hint = "在 设置 → 插件管理 安装 .qplug 音源后，这里会显示在线推荐内容"
                )

                error != null && content == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = error ?: "加载失败",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { refreshTick++ }) { Text("重试") }
                    }
                }

                content != null -> HomeContentList(
                    content = content,
                    sourceId = primary.id,
                    playerState = playerState,
                    canLogin = pluginManager.hasCapability(primary.id, "login"),
                    onLogin = { showLogin = true },
                    onOpenPlaylist = { pl ->
                        onNavigateToDetail("playlist", primary.id, pl.id, pl.name)
                    },
                    onPlaySong = { index -> staged.playAt(content.songs, index) },
                    onLongPressSong = { actionsSong = it },
                    actionsSong = actionsSong,
                    onDismissActions = { actionsSong = null }
                )

                else -> Box(
                    // 底部预留 MiniPlayer 悬浮区（与列表 contentPadding 一致），
                    // 空状态块在可见区域（MiniPlayer 上方）上下居中，而非全屏几何居中
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = MiniPlayerBottomReserve),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "暂无推荐内容",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "登录音源账号后可查看每日推荐与个性化歌单",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (primary != null && pluginManager.hasCapability(primary.id, "login")) {
                            FilledTonalButton(
                                onClick = { showLogin = true },
                                modifier = Modifier.padding(top = 16.dp)
                            ) { Text("登录音源账号") }
                        }
                        TextButton(
                            onClick = { refreshTick++ },
                            modifier = Modifier.padding(top = 4.dp)
                        ) { Text("刷新") }
                    }
                }
                }
            }
        }
    }

    ResolvingIndicator(staged.resolving)

    if (showLogin && primary != null && pluginManager.hasCapability(primary.id, "login")) {
        LoginDialog(
            pluginId = primary.id,
            pluginName = primary.name,
            onDismiss = { showLogin = false },
            onSessionChanged = { refreshTick++ },
            pluginManager = pluginManager
        )
    }
}

@Composable
private fun HomeContentList(
    content: HomeContent,
    sourceId: String,
    playerState: PlayerController.UiState,
    canLogin: Boolean = false,
    onLogin: () -> Unit = {},
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onPlaySong: (Int) -> Unit,
    onLongPressSong: (Song) -> Unit = {},
    actionsSong: Song? = null,
    onDismissActions: () -> Unit = {}
) {
    // 每日推荐 key 预计算：不混入 index，列表头部增删时后续项 key 不变（组合状态可复用）
    val songKeys = remember(content.songs) { stableKeys(content.songs) { it.id } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)
    ) {
        content.sections.forEach { section ->
            item(key = "hdr_${section.title}") {
                SectionHeader(section.title)
            }
            item(key = "row_${section.title}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(section.playlists, key = { it.id }) { playlist ->
                        PlaylistCard(playlist, modifier = Modifier.width(150.dp)) { onOpenPlaylist(playlist) }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (content.playlists.isNotEmpty()) {
            item(key = "hdr_推荐歌单") { SectionHeader("推荐歌单") }
            items(
                content.playlists.chunked(2),
                // 取块首 id 作 key：整块 joinToString 每次求值都拼长串，分块变化时 key 全量改
                key = { chunk -> chunk.first().id }
            ) { chunk ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    chunk.forEach { playlist ->
                        Box(modifier = Modifier.weight(1f)) {
                            PlaylistCard(playlist) { onOpenPlaylist(playlist) }
                        }
                    }
                    if (chunk.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        if (content.songs.isNotEmpty()) {
            item(key = "hdr_每日推荐") { SectionHeader("每日推荐") }
            itemsIndexed(
                content.songs,
                key = { index, _ -> songKeys[index] }
            ) { index, song ->
                val mediaId = "${song.sourceId}:${song.rawId}"
                SongActionsMenu(
                    song = song,
                    expanded = actionsSong == song,
                    onDismiss = onDismissActions
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
                        isPlaying = playerState.isPlaying && playerState.current?.mediaId == mediaId,
                        onLongClick = { onLongPressSong(song) },
                        onClick = { onPlaySong(index) }
                    )
                }
            }
        }

        if (content.sections.isEmpty() && content.playlists.isEmpty() && content.songs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.LibraryMusic,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "暂无推荐内容",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            text = "登录音源账号后可查看每日推荐与个性化歌单",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (canLogin) {
                            FilledTonalButton(
                                onClick = onLogin,
                                modifier = Modifier.padding(top = 16.dp)
                            ) { Text("登录音源账号") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 10.dp)
    )
}

@Composable
private fun PlaylistCard(playlist: OnlinePlaylist, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (playlist.artworkUrl != null) {
                AsyncImage(
                    model = playlist.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = playlist.description.ifBlank {
                playlist.ownerName ?: listOfNotNull(
                    playlist.trackCount.takeIf { it > 0 }?.let { "$it 首" },
                    playlist.playCount.takeIf { it > 0 }?.let { formatPlayCount(it) }
                ).joinToString(" · ").ifBlank { "歌单" }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState(title: String, hint: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 0..5 -> "夜深了"
    in 6..11 -> "早上好"
    in 12..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

private fun formatPlayCount(count: Int): String = when {
    count >= 100_000_000 -> "%.1f 亿".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f 万".format(count / 10_000.0)
    else -> count.toString()
}
