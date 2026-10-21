package com.xmgo.music.presentation.shared_components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmgo.music.data.cache.CachedMusicStore
import com.xmgo.music.data.datastore.FavoritesStore
import com.xmgo.music.plugin.model.Song
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

/**
 * 歌曲长按操作菜单：锚定在歌曲行旁的小浮层（非底部弹层），
 * 收藏 / 缓存该音乐（缓存中展示实时进度条）。
 * 缓存进行中时行底部叠加一条细进度条，列表页无需单独接入。
 *
 * 用法：列表页维护 `var actionsSong by remember { mutableStateOf<Song?>(null) }`，
 * 用 [SongActionsMenu] 包住每一行 SongRow，长按时 `actionsSong = song`。
 */
@Composable
fun SongActionsMenu(
    song: Song,
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val progress by cachedMusicStore.progress.collectAsState()
    val cachePct = progress[FavoritesStore.mediaIdOf(song)]?.fraction

    Box(modifier = modifier) {
        content()
        if (cachePct != null) {
            LinearProgressIndicator(
                progress = { if (cachePct >= 0f) cachePct else 0.5f },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
            )
        }
        if (expanded) {
            SongActionsDropdown(song = song, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun SongActionsDropdown(
    song: Song,
    onDismiss: () -> Unit
) {
    val favoritesStore: FavoritesStore = getKoin().get()
    val cachedMusicStore: CachedMusicStore = getKoin().get()
    val scope = rememberCoroutineScope()

    val favorites by favoritesStore.favorites.collectAsState(initial = emptyList())
    val progress by cachedMusicStore.progress.collectAsState()

    val mediaId = FavoritesStore.mediaIdOf(song)
    val isFavorite = favorites.any {
        it.sourceId == song.sourceId && it.rawId == song.rawId
    }
    val isCached = cachedMusicStore.isCached(mediaId)
    val cacheProgress = progress[mediaId]
    val isCaching = cacheProgress != null

    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(220.dp)
    ) {
        DropdownMenuItem(
            text = { Text(if (isFavorite) "取消收藏" else "收藏") },
            leadingIcon = {
                Icon(
                    imageVector = if (isFavorite) {
                        Icons.Rounded.Favorite
                    } else {
                        Icons.Rounded.FavoriteBorder
                    },
                    contentDescription = null,
                    tint = if (isFavorite) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            },
            onClick = {
                // NonCancellable：菜单关闭后 rememberCoroutineScope 会取消，
                // 避免 DataStore 写入被中途取消
                scope.launch {
                    withContext(NonCancellable) {
                        favoritesStore.setFavorite(song, !isFavorite)
                    }
                }
                onDismiss()
            }
        )
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        text = when {
                            isCaching -> {
                                val pct = cacheProgress.fraction
                                if (pct >= 0f) {
                                    "缓存中 ${"%.0f".format(pct * 100)}%"
                                } else {
                                    "缓存中…"
                                }
                            }
                            isCached -> "已缓存"
                            else -> "缓存该音乐"
                        }
                    )
                    if (isCaching) {
                        val pct = cacheProgress.fraction
                        LinearProgressIndicator(
                            progress = { if (pct >= 0f) pct else 0.5f },
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .width(140.dp)
                                .height(4.dp)
                        )
                    }
                }
            },
            leadingIcon = {
                if (isCaching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isCached) {
                            Icons.Rounded.DownloadDone
                        } else {
                            Icons.Rounded.Download
                        },
                        contentDescription = null,
                        tint = if (isCached) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            },
            enabled = !isCached && !isCaching,
            onClick = {
                cachedMusicStore.cacheAsync(song)
                onDismiss()
            }
        )
    }
}
