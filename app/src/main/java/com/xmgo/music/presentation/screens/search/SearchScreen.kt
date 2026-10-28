package com.xmgo.music.presentation.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xmgo.music.data.datastore.SearchHistoryStore
import com.xmgo.music.data.datastore.rememberPrimarySource
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.OnlineAlbum
import com.xmgo.music.plugin.model.OnlineArtist
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.presentation.shared_components.ResolvingIndicator
import com.xmgo.music.presentation.shared_components.SongActionsMenu
import com.xmgo.music.presentation.shared_components.SongRow
import com.xmgo.music.presentation.shared_components.rememberStagedPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/** 歌曲质量分：字段越全、标题匹配越准、主音源越靠前 = 越优。 */
private fun Song.rankScore(query: String, primary: String): Int {
    var score = 0
    if (!artworkUrl.isNullOrBlank()) score += 3
    if (artists.isNotEmpty()) score += 2
    if (!album.isNullOrBlank()) score += 1
    if (durationMs > 0) score += 2
    val q = query.trim().lowercase()
    if (q.isNotEmpty()) {
        val n = name.trim().lowercase()
        score += when {
            n == q -> 12
            n.startsWith(q) -> 7
            n.contains(q) -> 4
            artists.any { it.lowercase().contains(q) } -> 3
            else -> 0
        }
    }
    if (sourceId == primary) score += 1
    return score
}

private fun ArtistHit.rankScore(query: String, primary: String): Int {
    var score = 0
    if (!artist.artworkUrl.isNullOrBlank()) score += 3
    if (artist.songCount > 0) score += 2
    if (artist.albumCount > 0) score += 1
    val q = query.trim().lowercase()
    if (q.isNotEmpty()) {
        val n = artist.name.trim().lowercase()
        score += when {
            n == q -> 12
            n.startsWith(q) -> 7
            n.contains(q) -> 4
            else -> 0
        }
    }
    if (sourceId == primary) score += 1
    return score
}

private fun AlbumHit.rankScore(query: String, primary: String): Int {
    var score = 0
    if (!album.artworkUrl.isNullOrBlank()) score += 3
    if (album.trackCount > 0) score += 2
    if (album.artists.isNotEmpty()) score += 1
    val q = query.trim().lowercase()
    if (q.isNotEmpty()) {
        val n = album.name.trim().lowercase()
        score += when {
            n == q -> 12
            n.startsWith(q) -> 7
            n.contains(q) -> 4
            else -> 0
        }
    }
    if (sourceId == primary) score += 1
    return score
}

/**
 * 搜索页：输入即搜（防抖 400ms）、全音源并行搜索、按质量混排；
 * 歌曲/歌手/专辑为按钮式类目，切换即重搜，无需再点搜索按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onNavigateUp: () -> Unit,
    onNavigateToDetail: (kind: String, sourceId: String, id: String, title: String) -> Unit
) {
    val pluginManager: PluginManager = getKoin().get()
    val playerController: PlayerController = getKoin().get()
    val searchHistoryStore: SearchHistoryStore = getKoin().get()
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val pageCache: SearchPageCache = getKoin().get()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var actionsSong by remember { mutableStateOf<Song?>(null) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }

    val plugins by pluginManager.pluginsFlow.collectAsState()
    val primarySource by rememberPrimarySource()

    var hotWords by remember { mutableStateOf(pageCache.hotWords) }
    LaunchedEffect(plugins) {
        if (pageCache.hotWordsLoaded) {
            hotWords = pageCache.hotWords
            return@LaunchedEffect
        }
        // 热搜词：全音源并行取，去重合并（进程内只拉一次）
        val words = coroutineScope {
            plugins.map { p ->
                async {
                    runCatching { pluginManager.providerFor(p.id).hotSearch() }
                        .getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinct().take(20)
        }
        pageCache.hotWords = words
        pageCache.hotWordsLoaded = true
        hotWords = words
    }

    val history by searchHistoryStore.history.collectAsState(initial = emptyList())

    fun recordHistory() {
        scope.launch { searchHistoryStore.record(query) }
    }

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var artists by remember { mutableStateOf<List<ArtistHit>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumHit>>(emptyList()) }
    // 每个音源当前类目的下一页游标（null = 无更多）
    var songCursors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var artistCursors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var albumCursors by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val playerState by playerController.ui.collectAsState()
    val staged = rememberStagedPlayer(pluginManager, playerController, cachedMusicStore)

    // 必须 remember 持有：普通局部变量每次重组重置为 null，旧搜索协程无法取消，
    // 多个搜索并发写结果产生竞态（结果互相覆盖 / loading 提前置 false）
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun clearResults() {
        songs = emptyList()
        artists = emptyList()
        albums = emptyList()
        songCursors = emptyMap()
        artistCursors = emptyMap()
        albumCursors = emptyMap()
        error = null
    }

    /** 全音源并行搜索当前 query + tab，质量混排。 */
    fun searchAll() {
        val q = query.trim()
        if (q.isEmpty()) {
            clearResults()
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            loading = true
            error = null
            try {
                when (tab) {
                    1 -> {
                        val sources = plugins.filter {
                            pluginManager.hasCapability(it.id, "searchArtists")
                        }
                        if (sources.isEmpty()) error = "未安装可用插件"
                        val pages = coroutineScope {
                            sources.map { p ->
                                async {
                                    runCatching {
                                        p.id to pluginManager.providerFor(p.id)
                                            .searchArtists(q, null)
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        }
                        artists = pages
                            .flatMap { (id, page) -> page.items.map { ArtistHit(id, it) } }
                            .distinctBy { "${it.sourceId}:${it.artist.id}" }
                            .sortedByDescending { it.rankScore(q, primarySource) }
                        artistCursors = sources.associate { p ->
                            p.id to pages.firstOrNull { it.first == p.id }?.second?.nextCursor
                        }
                    }
                    2 -> {
                        val sources = plugins.filter {
                            pluginManager.hasCapability(it.id, "searchAlbums")
                        }
                        if (sources.isEmpty()) error = "未安装可用插件"
                        val pages = coroutineScope {
                            sources.map { p ->
                                async {
                                    runCatching {
                                        p.id to pluginManager.providerFor(p.id)
                                            .searchAlbums(q, null)
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        }
                        albums = pages
                            .flatMap { (id, page) -> page.items.map { AlbumHit(id, it) } }
                            .distinctBy { "${it.sourceId}:${it.album.id}" }
                            .sortedByDescending { it.rankScore(q, primarySource) }
                        albumCursors = sources.associate { p ->
                            p.id to pages.firstOrNull { it.first == p.id }?.second?.nextCursor
                        }
                    }
                    else -> {
                        val sources = plugins.filter {
                            pluginManager.hasCapability(it.id, "searchSongs")
                        }
                        if (sources.isEmpty()) error = "未安装可用插件"
                        val pages = coroutineScope {
                            sources.map { p ->
                                async {
                                    runCatching {
                                        p.id to pluginManager.providerFor(p.id)
                                            .searchSongs(q, null)
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        }
                        songs = pages
                            .flatMap { (_, page) -> page.items }
                            .distinctBy { "${it.sourceId}:${it.rawId}" }
                            .sortedByDescending { it.rankScore(q, primarySource) }
                        songCursors = sources.associate { p ->
                            p.id to pages.firstOrNull { it.first == p.id }?.second?.let {
                                if (it.hasMore) it.nextCursor else null
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            loading = false
            // 写入页面缓存：返回搜索页时命中即恢复，不再重搜。
            // 注意 searchAll/loadMore 都要写：loadMore 的 cursor 更新也得进缓存
            pageCache.put(
                query.trim(), tab,
                SearchSnapshot(
                    songs = songs,
                    artists = artists,
                    albums = albums,
                    songCursors = songCursors,
                    artistCursors = artistCursors,
                    albumCursors = albumCursors,
                    error = error
                )
            )
        }
    }

    /** 加载更多：各音源并行取下一页，合并重排。 */
    fun loadMore() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob = scope.launch {
            loading = true
            try {
                when (tab) {
                    1 -> {
                        val pending = artistCursors.filterValues { !it.isNullOrBlank() }.keys
                        val pages = coroutineScope {
                            pending.map { id ->
                                async {
                                    runCatching {
                                        id to pluginManager.providerFor(id)
                                            .searchArtists(q, artistCursors[id])
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        }
                        artists = (artists + pages.flatMap { (id, page) ->
                            page.items.map { ArtistHit(id, it) }
                        }).distinctBy { "${it.sourceId}:${it.artist.id}" }
                            .sortedByDescending { it.rankScore(q, primarySource) }
                        artistCursors = artistCursors + pages.associate { (id, page) ->
                            id to page.nextCursor
                        }
                    }
                    2 -> {
                        val pending = albumCursors.filterValues { !it.isNullOrBlank() }.keys
                        val pages = coroutineScope {
                            pending.map { id ->
                                async {
                                    runCatching {
                                        id to pluginManager.providerFor(id)
                                            .searchAlbums(q, albumCursors[id])
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        }
                        albums = (albums + pages.flatMap { (id, page) ->
                            page.items.map { AlbumHit(id, it) }
                        }).distinctBy { "${it.sourceId}:${it.album.id}" }
                            .sortedByDescending { it.rankScore(q, primarySource) }
                        albumCursors = albumCursors + pages.associate { (id, page) ->
                            id to page.nextCursor
                        }
                    }
                    else -> {
                        val pending = songCursors.filterValues { !it.isNullOrBlank() }.keys
                        val pages = coroutineScope {
                            pending.map { id ->
                                async {
                                    runCatching {
                                        id to pluginManager.providerFor(id)
                                            .searchSongs(q, songCursors[id])
                                    }.getOrNull()
                                }
                            }.awaitAll().filterNotNull()
                        }
                        songs = (songs + pages.flatMap { (_, page) -> page.items })
                            .distinctBy { "${it.sourceId}:${it.rawId}" }
                            .sortedByDescending { it.rankScore(q, primarySource) }
                        songCursors = songCursors + pages.associate { (id, page) ->
                            id to if (page.hasMore) page.nextCursor else null
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            loading = false
            // 写入页面缓存：返回搜索页时命中即恢复，不再重搜
            pageCache.put(
                query.trim(), tab,
                SearchSnapshot(
                    songs = songs,
                    artists = artists,
                    albums = albums,
                    songCursors = songCursors,
                    artistCursors = artistCursors,
                    albumCursors = albumCursors,
                    error = error
                )
            )
        }
    }

    // 搜索触发（query/tab 任一变化）：命中缓存直接恢复不重搜——
    // 原先 query/tab 两个 LaunchedEffect 并发，返回页面时 tab 的无条件 searchAll
    // 会把缓存恢复的结果又覆盖成重搜，合并为单入口统一走缓存判定
    var lastSearchedQuery by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, tab) {
        if (query.isBlank()) {
            searchJob?.cancel()
            clearResults()
            lastSearchedQuery = null
            return@LaunchedEffect
        }
        // 返回本页 / 切换类目回来：命中缓存直接恢复上次结果，免防抖 + 全音源重搜
        pageCache.snapshot(query, tab)?.let { cached ->
            songs = cached.songs
            artists = cached.artists
            albums = cached.albums
            songCursors = cached.songCursors
            artistCursors = cached.artistCursors
            albumCursors = cached.albumCursors
            error = cached.error
            loading = false
            return@LaunchedEffect
        }
        // 输入防抖只作用于 query 变化；tab 切换立即搜（不等防抖）
        val queryChanged = query != lastSearchedQuery
        lastSearchedQuery = query
        if (queryChanged) delay(400)
        searchAll()
    }

    fun playOnline(index: Int) {
        if (songs.isEmpty()) return
        // 起播前收起键盘 + 记录搜索历史（StagedPlayer.onStart 回调）
        staged.onStart = {
            focusManager.clearFocus()
            recordHistory()
        }
        staged.playAt(songs, index)
    }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
                ) {
                    AnimatedBackIcon(visible = !inputFocused, onNavigateUp = onNavigateUp)
                    // Material 3 搜索规范：清除 × 与加载态放框内 trailingIcon；
                    // 提交走键盘「搜索」键，无框外按钮
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("搜索歌曲/歌手/专辑") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isNotBlank()) {
                                focusManager.clearFocus()
                                searchAll()
                            }
                        }),
                        trailingIcon = {
                            when {
                                loading -> CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp
                                )
                                // 点 × 清空并保持焦点，回到历史/热搜视图
                                query.isNotEmpty() -> IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = "清除"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { inputFocused = it.isFocused }
                    )
                }

                // 类目：按钮式切换（切换即重搜），居中展示
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    CategoryChip("歌曲", selected = tab == 0) { tab = 0 }
                    CategoryChip("歌手", selected = tab == 1) { tab = 1 }
                    CategoryChip("专辑", selected = tab == 2) { tab = 2 }
                }

                // 搜索错误 + staged 播放失败合并展示
                (error ?: staged.error)?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                when {
                    query.isBlank() -> HistoryAndHotSearch(
                        history = history,
                        words = hotWords,
                        onPick = { word -> query = word },
                        onClearHistory = {
                            scope.launch { searchHistoryStore.clear() }
                        }
                    )

                    else -> {
                        when (tab) {
                            1 -> ArtistResults(
                                results = artists,
                                hasMore = artistCursors.values.any { !it.isNullOrBlank() },
                                onLoadMore = ::loadMore,
                                onOpen = { hit ->
                                    recordHistory()
                                    onNavigateToDetail(
                                        "artist", hit.sourceId, hit.artist.id, hit.artist.name
                                    )
                                }
                            )
                            2 -> AlbumResults(
                                results = albums,
                                hasMore = albumCursors.values.any { !it.isNullOrBlank() },
                                onLoadMore = ::loadMore,
                                onOpen = { hit ->
                                    recordHistory()
                                    onNavigateToDetail(
                                        "album", hit.sourceId, hit.album.id, hit.album.name
                                    )
                                }
                            )
                            else -> SongResults(
                                results = songs,
                                hasMore = songCursors.values.any { it.isNullOrBlank().not() },
                                playerState = playerState,
                                onLoadMore = ::loadMore,
                                onPlay = ::playOnline,
                                onLongPressSong = { actionsSong = it },
                                loading = loading,
                                actionsSong = actionsSong,
                                onDismissActions = { actionsSong = null }
                            )
                        }
                    }
                }
            }

            ResolvingIndicator(staged.resolving)
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun AnimatedBackIcon(visible: Boolean, onNavigateUp: () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut()
    ) {
        IconButton(onClick = onNavigateUp) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "返回"
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryAndHotSearch(
    history: List<String>,
    words: List<String>,
    onPick: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    if (history.isEmpty() && words.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "输入关键词搜索在线曲库\n结果可直接点按播放",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp)
                )
            }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (history.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "搜索历史",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearHistory) {
                        Text("清空", style = MaterialTheme.typography.labelMedium)
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    history.forEach { word ->
                        FilterChip(
                            selected = false,
                            onClick = { onPick(word) },
                            label = { Text(word) }
                        )
                    }
                }
            }
        }
        if (words.isNotEmpty()) {
            item {
                Text(
                    text = "热门搜索",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    words.forEach { word ->
                        FilterChip(
                            selected = false,
                            onClick = { onPick(word) },
                            label = { Text(word) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SongResults(
    results: List<Song>,
    hasMore: Boolean,
    playerState: PlayerController.UiState,
    loading: Boolean,
    onLoadMore: () -> Unit,
    onPlay: (Int) -> Unit,
    onLongPressSong: (Song) -> Unit = {},
    actionsSong: Song? = null,
    onDismissActions: () -> Unit = {}
) {
    if (results.isEmpty()) {
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            EmptyHint("没有找到相关歌曲")
        }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
        itemsIndexed(results, key = { _, it -> "${it.sourceId}:${it.rawId}" }) { index, song ->
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
                    onClick = { onPlay(index) }
                )
            }
        }
        loadMoreItem(hasMore, onLoadMore)
        }
        com.xmgo.music.presentation.shared_components.ScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 164.dp)
        )
    }
}

@Composable
private fun ArtistResults(
    results: List<ArtistHit>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (ArtistHit) -> Unit
) {
    if (results.isEmpty()) {
        EmptyHint("没有找到相关歌手")
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
        itemsIndexed(results, key = { _, it -> "${it.sourceId}:${it.artist.id}" }) { _, hit ->
            ResultRow(
                artwork = hit.artist.artworkUrl,
                title = hit.artist.name,
                subtitle = listOfNotNull(
                    "歌手",
                    hit.artist.songCount.takeIf { it > 0 }?.let { "$it 首" }
                ).joinToString(" · "),
                round = true,
                onClick = { onOpen(hit) }
            )
        }
        loadMoreItem(hasMore, onLoadMore)
        }
        com.xmgo.music.presentation.shared_components.ScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 164.dp)
        )
    }
}

@Composable
private fun AlbumResults(
    results: List<AlbumHit>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onOpen: (AlbumHit) -> Unit
) {
    if (results.isEmpty()) {
        EmptyHint("没有找到相关专辑")
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
        itemsIndexed(results, key = { _, it -> "${it.sourceId}:${it.album.id}" }) { _, hit ->
            ResultRow(
                artwork = hit.album.artworkUrl,
                title = hit.album.name,
                subtitle = listOfNotNull(
                    "专辑",
                    hit.album.artists.joinToString(" / ").ifEmpty { null }
                ).joinToString(" · "),
                round = false,
                onClick = { onOpen(hit) }
            )
        }
        loadMoreItem(hasMore, onLoadMore)
        }
        com.xmgo.music.presentation.shared_components.ScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 164.dp)
        )
    }
}

@Composable
private fun ResultRow(
    artwork: String?,
    title: String,
    subtitle: String,
    round: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(if (round) CircleShape else RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (artwork != null) {
                AsyncImage(
                    model = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun LazyListScope.loadMoreItem(visible: Boolean, onLoadMore: () -> Unit) {
    if (!visible) return
    item {
        TextButton(
            onClick = onLoadMore,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) { Text("加载更多") }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.SearchOff,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp)
            )
        }
    }
}
