package com.xmgo.music.presentation.screens.cached

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.xmgo.music.data.cache.CachedMusicGroup
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.data.cache.CachedSongEntry
import androidx.media3.common.Player
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.presentation.shared_components.EmptyState
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import com.xmgo.music.presentation.shared_components.SongRow
import com.xmgo.music.presentation.shared_components.rememberStagedPlayer
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import kotlin.math.roundToInt

/** 分组筛选：全部 / 指定分组。 */
private sealed interface GroupFilter {
    data object All : GroupFilter
    data class Group(val id: String, val name: String) : GroupFilter
}

/**
 * 缓存音乐页：横滑分组 chips（全部/自建分组，点击筛选，长按自建分组重命名/删除），
 * 右上角「+」新建分组；左滑删除（确认弹窗）+ 长按进入多选（批量删除/移动到分组）。
 * 点击直接用本地缓存文件起播（经 playSongsStaged 的 cache 参数命中）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CachedMusicScreen(onNavigateUp: () -> Unit) {
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val playerController: PlayerController = getKoin().get()
    val pluginManager: PluginManager = getKoin().get()
    val scope = rememberCoroutineScope()

    val cached by cachedMusicStore.cached.collectAsState()
    val groups by cachedMusicStore.groups.collectAsState()
    val progress by cachedMusicStore.progress.collectAsState()
    val playerState by playerController.ui.collectAsState()

    var filter by remember { mutableStateOf<GroupFilter>(GroupFilter.All) }
    // 多选模式：null = 未进入；非空 = 选中的 mediaId 集合
    var selection by remember { mutableStateOf<Set<String>?>(null) }
    val staged = rememberStagedPlayer(
        pluginManager, playerController, cachedMusicStore, errorMessage = "播放失败"
    )
    // 长按自建分组 chip 弹出的操作菜单
    var chipMenuGroupId by remember { mutableStateOf<String?>(null) }

    // 待确认删除
    var pendingRemoveOne by remember { mutableStateOf<CachedSongEntry?>(null) }
    var pendingRemoveMany by remember { mutableStateOf(false) }
    // 左滑展开行的收起信号：删除弹窗关闭时 +1，各行 LaunchedEffect 收到后回弹
    var swipeResetTick by remember { mutableStateOf(0) }
    // 分组操作弹窗
    var newGroupName by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<CachedMusicGroup?>(null) }
    var deleteGroup by remember { mutableStateOf<CachedMusicGroup?>(null) }
    var moveToGroup by remember { mutableStateOf(false) }
    var nameDialogText by remember { mutableStateOf("") }
    var nameDialogError by remember { mutableStateOf<String?>(null) }

    val displayEntries = remember(cached, filter) {
        when (filter) {
            GroupFilter.All -> cached
            is GroupFilter.Group -> cached.filter { it.groupId == (filter as GroupFilter.Group).id }
        }
    }
    val displaySongs = remember(displayEntries) { displayEntries.map { it.song } }
    val selected = selection ?: emptySet()
    val inMultiSelect = selection != null
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    fun playAt(index: Int) {
        if (displaySongs.isEmpty()) return
        staged.playAt(displaySongs, index)
    }

    Scaffold(
        topBar = {
            if (inMultiSelect) {
                TopAppBar(
                    title = { Text("已选 ${selected.size} 首") },
                    navigationIcon = {
                        IconButton(onClick = { selection = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            // 全选当前筛选后的可见条目；已全选则清空
                            val visibleIds = displayEntries
                                .map { cachedMusicStore.mediaIdOf(it.song) }
                                .toSet()
                            selection = if (visibleIds.isNotEmpty() && selected.containsAll(visibleIds)) {
                                emptySet()
                            } else {
                                visibleIds
                            }
                        }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = "全选")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("缓存音乐") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    actions = {
                        // 播放模式三态切换：顺序 → 随机 → 单曲循环
                        IconButton(onClick = { playerController.cyclePlayMode() }) {
                            Icon(
                                imageVector = when {
                                    playerState.shuffleEnabled -> Icons.Rounded.Shuffle
                                    playerState.repeatMode == Player.REPEAT_MODE_ONE ->
                                        Icons.Rounded.RepeatOne
                                    else -> Icons.Rounded.Repeat
                                },
                                contentDescription = when {
                                    playerState.shuffleEnabled -> "随机播放，点击切换单曲循环"
                                    playerState.repeatMode == Player.REPEAT_MODE_ONE ->
                                        "单曲循环，点击切换顺序播放"
                                    else -> "顺序播放，点击切换随机播放"
                                }
                            )
                        }
                        IconButton(onClick = {
                            nameDialogText = ""
                            nameDialogError = null
                            newGroupName = true
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "新建分组"
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {},
        floatingActionButton = {
            // 多选模式隐藏：底部视觉让位给列表上方的操作栏
            com.xmgo.music.presentation.shared_components.ScrollToTopButton(
                listState = listState,
                modifier = Modifier.padding(bottom = 164.dp),
                visible = !inMultiSelect
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!inMultiSelect) {
                GroupChipsRow(
                    filter = filter,
                    groups = groups,
                    menuGroupId = chipMenuGroupId,
                    onSelect = { filter = it },
                    onMenuToggle = { chipMenuGroupId = it },
                    onRename = { group ->
                        nameDialogText = group.name
                        nameDialogError = null
                        renameGroup = group
                    },
                    onDelete = { deleteGroup = it }
                )
            } else {
                // 多选操作栏：放音乐列表上方（原在底部，与 MiniPlayer 抢位）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { pendingRemoveMany = true },
                        enabled = selected.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("删除")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { moveToGroup = true },
                        enabled = selected.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Rounded.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("移动到分组")
                    }
                }
            }
            if (displayEntries.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Download,
                    title = "还没有缓存歌曲",
                    hint = "音乐列表长按歌曲选择「缓存该音乐」",
                    modifier = Modifier.padding(bottom = MiniPlayerBottomReserve)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)
                ) {
                    itemsIndexed(displayEntries, key = { _, it -> FavoritesStore.mediaIdOf(it.song) }) { index, entry ->
                        val song = entry.song
                        val mediaId = FavoritesStore.mediaIdOf(song)
                        val cachePct = progress[mediaId]

                        val row: @Composable () -> Unit = {
                            SongRow(
                                title = song.name,
                                artworkUrl = song.artworkUrl,
                                subtitle = listOfNotNull(
                                    song.artists.joinToString(" / ").ifEmpty { null },
                                    song.album
                                ).joinToString(" · "),
                                durationMs = song.durationMs,
                                showDuration = !inMultiSelect,
                                isCurrent = playerState.current?.mediaId == mediaId,
                                isPlaying = playerState.isPlaying &&
                                    playerState.current?.mediaId == mediaId,
                                onClick = {
                                    if (inMultiSelect) {
                                        selection = if (mediaId in selected) {
                                            selected - mediaId
                                        } else {
                                            selected + mediaId
                                        }
                                    } else {
                                        playAt(index)
                                    }
                                },
                                onLongClick = {
                                    // 长按进入多选模式（与在线列表的长按菜单区分：此页无菜单）
                                    if (!inMultiSelect) {
                                        selection = setOf(mediaId)
                                    }
                                },
                                trailing = {
                                    if (inMultiSelect) {
                                        Checkbox(
                                            checked = mediaId in selected,
                                            onCheckedChange = null
                                        )
                                    } else if (cachePct != null) {
                                        val pct = cachePct.fraction
                                        LinearProgressIndicator(
                                            progress = { if (pct >= 0f) pct else 0.5f },
                                            modifier = Modifier.width(56.dp)
                                        )
                                    }
                                }
                            )
                        }

                        if (inMultiSelect) {
                            row()
                        } else {
                            SwipeToDeleteRow(
                                onDelete = { pendingRemoveOne = entry },
                                resetSignal = swipeResetTick,
                                modifier = Modifier.animateItem()
                            ) { row() }
                        }
                    }
                }
            }

            staged.error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 120.dp)
                )
            }
        }
    }

    // ---------------------------------------------------------------- 弹窗

    pendingRemoveOne?.let { entry ->
        AlertDialog(
            onDismissRequest = {
                pendingRemoveOne = null
                swipeResetTick++
            },
            title = { Text("删除缓存") },
            text = { Text("删除「${entry.song.name}」的本地缓存？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mediaId = FavoritesStore.mediaIdOf(entry.song)
                        pendingRemoveOne = null
                        swipeResetTick++
                        scope.launch { cachedMusicStore.remove(mediaId) }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingRemoveOne = null
                        swipeResetTick++
                    }
                ) { Text("取消") }
            }
        )
    }

    if (pendingRemoveMany) {
        AlertDialog(
            onDismissRequest = { pendingRemoveMany = false },
            title = { Text("删除缓存") },
            text = { Text("删除选中的 ${selected.size} 首歌曲缓存？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoveMany = false
                        val ids = selected
                        selection = null
                        scope.launch { cachedMusicStore.removeMany(ids) }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoveMany = false }) { Text("取消") }
            }
        )
    }

    if (moveToGroup) {
        MoveToGroupDialog(
            groups = groups,
            onConfirm = { groupId ->
                moveToGroup = false
                val ids = selected
                selection = null
                scope.launch { cachedMusicStore.moveToGroup(ids, groupId) }
            },
            onDismiss = { moveToGroup = false },
            onCreateGroup = {
                moveToGroup = false
                nameDialogText = ""
                nameDialogError = null
                newGroupName = true
            }
        )
    }

    if (newGroupName) {
        NameDialog(
            title = "新建分组",
            initial = nameDialogText,
            error = nameDialogError,
            onConfirm = { name ->
                if (name.isBlank()) {
                    nameDialogError = "请输入分组名"
                    return@NameDialog
                }
                if (groups.any { it.name == name.trim() }) {
                    nameDialogError = "已存在同名分组"
                    return@NameDialog
                }
                newGroupName = false
                scope.launch { cachedMusicStore.createGroup(name) }
            },
            onDismiss = { newGroupName = false }
        )
    }

    renameGroup?.let { group ->
        NameDialog(
            title = "重命名分组",
            initial = group.name,
            error = nameDialogError,
            onConfirm = { name ->
                if (name.isBlank()) {
                    nameDialogError = "请输入分组名"
                    return@NameDialog
                }
                renameGroup = null
                nameDialogError = null
                scope.launch { cachedMusicStore.renameGroup(group.id, name) }
            },
            onDismiss = { renameGroup = null }
        )
    }

    deleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deleteGroup = null },
            title = { Text("删除分组") },
            text = { Text("删除分组「${group.name}」？组内歌曲将回到未分组（缓存不会删除）") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteGroup = null
                        if ((filter as? GroupFilter.Group)?.id == group.id) {
                            filter = GroupFilter.All
                        }
                        scope.launch { cachedMusicStore.deleteGroup(group.id) }
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteGroup = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 横滑分组 chips：全部 / 自建分组（统一样式的圆角 chip，选中高亮）。
 * 长按自建分组 chip 弹出菜单（重命名 / 删除分组）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChipsRow(
    filter: GroupFilter,
    groups: List<CachedMusicGroup>,
    menuGroupId: String?,
    onSelect: (GroupFilter) -> Unit,
    onMenuToggle: (String?) -> Unit,
    onRename: (CachedMusicGroup) -> Unit,
    onDelete: (CachedMusicGroup) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        item {
            GroupChip(
                label = "全部",
                selected = filter is GroupFilter.All,
                onClick = { onSelect(GroupFilter.All) }
            )
        }
        items(groups, key = { it.id }) { group ->
            Box {
                GroupChip(
                    label = group.name,
                    selected = (filter as? GroupFilter.Group)?.id == group.id,
                    onClick = { onSelect(GroupFilter.Group(group.id, group.name)) },
                    onLongClick = { onMenuToggle(group.id) }
                )
                if (menuGroupId == group.id) {
                    DropdownMenu(
                        expanded = true,
                        onDismissRequest = { onMenuToggle(null) }
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                onMenuToggle(null)
                                onRename(group)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除分组", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                onMenuToggle(null)
                                onDelete(group)
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 统一高度/圆角的分组 chip（点击筛选；有 onLongClick 时支持长按）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(20.dp)
    val clickable = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = shape,
        modifier = Modifier.clip(shape).then(clickable)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * 左滑露出右侧删除按钮（不直接移除，点击按钮后走确认弹窗）。
 * 松手时超过一半宽度则吸附展开，否则回弹。
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    resetSignal: Int = 0,
    content: @Composable () -> Unit
) {
    val revealWidthPx = with(LocalDensity.current) { 72.dp.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // 外部信号变化（如删除弹窗关闭）时收起左滑
    LaunchedEffect(resetSignal) {
        if (resetSignal > 0 && offset.value != 0f) {
            offset.animateTo(0f, tween(200))
        }
    }

    Box(modifier = modifier) {
        // 背景：右侧删除按钮（matchParentSize 跟随内容层尺寸）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd
        ) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        // 内容层：wrap 高度 + fillMaxWidth（不能 matchParentSize，否则 Box 无固有尺寸变 0 高）
        Box(
            modifier = Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target = if (offset.value < -revealWidthPx / 2) {
                                    -revealWidthPx
                                } else {
                                    0f
                                }
                                offset.animateTo(target, tween(200))
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offset.value + dragAmount)
                                .coerceIn(-revealWidthPx, 0f)
                            scope.launch { offset.snapTo(next) }
                        }
                    )
                }
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
        ) {
            content()
        }
    }
}

@Composable
private fun MoveToGroupDialog(
    groups: List<CachedMusicGroup>,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    onCreateGroup: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分组") },
        text = {
            Column {
                Text(
                    text = "未分组",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConfirm(null) }
                        .padding(vertical = 12.dp)
                )
                groups.forEach { group ->
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(group.id) }
                            .padding(vertical = 12.dp)
                    )
                }
                Text(
                    text = "＋ 新建分组…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onCreateGroup)
                        .padding(vertical = 12.dp)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
