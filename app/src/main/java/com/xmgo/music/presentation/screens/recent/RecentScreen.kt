package com.xmgo.music.presentation.screens.recent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.data.datastore.RecentStore
import com.xmgo.music.data.local.dao.RecentPlayDao
import com.xmgo.music.data.repository.MediaStoreRepository
import com.xmgo.music.playback.PlayableEntry
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.playback.playEntriesStaged
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.presentation.shared_components.EmptyState
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import com.xmgo.music.presentation.shared_components.SongActionsMenu
import com.xmgo.music.presentation.shared_components.SongRow
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/** 最近播放的统一列表项（本地 + 在线，按 playedAt 合并降序）。 */
private data class RecentUiEntry(
    val mediaId: String,
    val title: String,
    val subtitle: String,
    val artwork: String?,
    val durationMs: Long,
    val playedAt: Long,
    val entry: PlayableEntry
)

/**
 * 最近播放页：本地（Room recent_plays）+ 在线（RecentStore）合并展示，
 * 点击经分阶段起播（本地直取、在线 resolveStream）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(onNavigateUp: () -> Unit) {
    val recentPlayDao: RecentPlayDao = getKoin().get()
    val playerController: PlayerController = getKoin().get()
    val recentStore: RecentStore = getKoin().get()
    val pluginManager: PluginManager = getKoin().get()
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val scope = rememberCoroutineScope()
    var actionsSong by remember { mutableStateOf<Song?>(null) }

    val localRecents by recentPlayDao.observeRecentSongs().collectAsState(initial = emptyList())
    val onlineRecents by recentStore.recents.collectAsState(initial = emptyList())
    val playerState by playerController.ui.collectAsState()
    var resolving by remember { mutableStateOf(false) }

    val entries = remember(localRecents, onlineRecents) {
        (localRecents.map { item ->
            RecentUiEntry(
                mediaId = item.song.mediaId,
                title = item.song.title,
                subtitle = "${item.song.artist} · ${item.song.album}",
                artwork = MediaStoreRepository.albumArtUri(item.song.albumId).toString(),
                durationMs = item.song.durationMs,
                playedAt = item.playedAt,
                entry = PlayableEntry.Local(item.song)
            )
        } + onlineRecents.map { item ->
            RecentUiEntry(
                mediaId = FavoritesStore.mediaIdOf(item.song),
                title = item.song.name,
                subtitle = item.song.artists.joinToString(" / ")
                    .ifEmpty { item.song.album ?: "在线歌曲" },
                artwork = item.song.artworkUrl,
                durationMs = item.song.durationMs,
                playedAt = item.playedAt,
                entry = PlayableEntry.Online(item.song)
            )
        }).sortedByDescending { it.playedAt }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("最近播放") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    recentPlayDao.clearRecents()
                                    recentStore.clear()
                                }
                            }
                        ) {
                            Text("清空")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (resolving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    title = "还没有播放记录",
                    iconSize = 24.dp
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)
                ) {
                    itemsIndexed(entries, key = { _, it -> it.mediaId }) { index, item ->
                        val onlineSong = (item.entry as? PlayableEntry.Online)?.song
                        val row: @Composable () -> Unit = {
                            SongRow(
                                title = item.title,
                                artworkUrl = item.artwork,
                                subtitle = item.subtitle,
                                durationMs = item.durationMs,
                                isCurrent = playerState.current?.mediaId == item.mediaId,
                                isPlaying = playerState.isPlaying &&
                                    playerState.current?.mediaId == item.mediaId,
                                onClick = {
                                    scope.launch {
                                        resolving = true
                                        runCatching {
                                            playEntriesStaged(
                                                entries = entries.map { it.entry },
                                                index = index.coerceAtLeast(0),
                                                pluginManager = pluginManager,
                                                playerController = playerController,
                                                cache = cachedMusicStore,
                                                onStarted = { resolving = false }
                                            )
                                        }
                                        resolving = false
                                    }
                                },
                                onLongClick = onlineSong?.let { song -> { actionsSong = song } }
                            )
                        }
                        if (onlineSong != null) {
                            SongActionsMenu(
                                song = onlineSong,
                                expanded = actionsSong == onlineSong,
                                onDismiss = { actionsSong = null }
                            ) { row() }
                        } else {
                            row()
                        }
                    }
                }
            }
        }
    }
}
