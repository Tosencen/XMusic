package com.xmgo.music.presentation.screens.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.xmgo.music.playback.playSongsStaged
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.Song
import com.xmgo.music.presentation.shared_components.ResolvingIndicator
import com.xmgo.music.presentation.shared_components.SongActionsMenu
import com.xmgo.music.presentation.shared_components.SongRow
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

    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun playAt(index: Int) {
        if (favorites.isEmpty()) return
        scope.launch {
            resolving = true
            val ok = playSongsStaged(
                favorites, index, pluginManager, playerController, cachedMusicStore
            ) {
                resolving = false
            }
            if (!ok) {
                resolving = false
                error = "解析播放地址失败"
            }
        }
    }

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
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (favorites.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(bottom = 12.dp)
                                .size(40.dp)
                        )
                        Text(
                            text = "还没有收藏歌曲",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "全屏播放页点击心形按钮即可收藏",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(bottom = 108.dp)
                ) {
                    items(
                        favorites,
                        key = { "${it.sourceId}:${it.rawId}" }
                    ) { song ->
                        val mediaId = "${song.sourceId}:${song.rawId}"
                        val index = favorites.indexOfFirst {
                            "${it.sourceId}:${it.rawId}" == mediaId
                        }
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
                                onClick = { playAt(index) },
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

            error?.let { message ->
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

    ResolvingIndicator(resolving)
}
