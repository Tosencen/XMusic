package com.xmgo.music.presentation.screens.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.presentation.shared_components.EmptyState
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import com.xmgo.music.presentation.shared_components.ResolvingIndicator
import com.xmgo.music.presentation.shared_components.SongActionsMenu
import com.xmgo.music.presentation.shared_components.SongRow
import com.xmgo.music.presentation.shared_components.rememberStagedPlayer
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 收藏页：收藏的在线歌曲，点击经插件解析起播；点心形移除收藏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onNavigateUp: () -> Unit) {
    val favoritesStore: FavoritesStore = getKoin().get()
    val playerController: PlayerController = getKoin().get()
    val pluginManager: PluginManager = getKoin().get()
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val scope = rememberCoroutineScope()
    var actionsSong by remember { mutableStateOf<Song?>(null) }

    val favorites by favoritesStore.favorites.collectAsState(initial = emptyList())
    val playerState by playerController.ui.collectAsState()

    val staged = rememberStagedPlayer(pluginManager, playerController, cachedMusicStore)

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收藏") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            com.xmgo.music.presentation.shared_components.ScrollToTopButton(
                listState = listState,
                modifier = Modifier.padding(bottom = 164.dp)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (favorites.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "还没有收藏歌曲",
                    hint = "全屏播放页点击心形按钮即可收藏",
                    modifier = Modifier.padding(padding)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)
                ) {
                    itemsIndexed(
                        favorites,
                        key = { _, it -> "${it.sourceId}:${it.rawId}" }
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
                                onClick = { staged.playAt(favorites, index) },
                                trailing = {
                                    IconButton(
                                        onClick = {
                                            scope.launch { favoritesStore.setFavorite(song, false) }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Favorite,
                                            contentDescription = "取消收藏",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            )
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
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                )
            }
        }
    }

    ResolvingIndicator(staged.resolving)
}
