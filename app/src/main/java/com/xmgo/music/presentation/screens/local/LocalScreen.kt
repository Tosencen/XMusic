package com.xmgo.music.presentation.screens.local

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xmgo.music.R
import com.xmgo.music.data.repository.MediaStoreRepository
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.playback.toMediaItem
import com.xmgo.music.presentation.shared_components.MiniPlayerBottomReserve
import com.xmgo.music.presentation.shared_components.SongRow
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 本地音乐页：MediaStore → Room 扫描，点击整列播放（原首页本地列表迁入）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LocalScreen(
    onNavigateUp: () -> Unit,
    repository: MediaStoreRepository = getKoin().get(),
    playerController: PlayerController = getKoin().get()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val songs by repository.observeSongs().collectAsState(initial = emptyList())
    val playerState by playerController.ui.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            scope.launch { repository.sync() }
        }
    }

    fun ensurePermissionsAndSync() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_AUDIO)
            else add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            scope.launch { repository.sync() }
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        ensurePermissionsAndSync()
        repository.observeLibrary().collect { /* 变化时自动重扫 */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地音乐 · ${songs.size} 首") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (songs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "未找到本地歌曲",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "允许音频权限后自动扫描设备音乐",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = MiniPlayerBottomReserve)
                ) {
                    itemsIndexed(songs, key = { _, it -> it.id }) { index, song ->
                        SongRow(
                            title = song.title,
                            artworkUrl = com.xmgo.music.data.repository.MediaStoreRepository.albumArtUri(song.albumId).toString(),
                            subtitle = "${song.artist} · ${song.album}",
                            durationMs = song.durationMs,
                            isCurrent = playerState.current?.mediaId == song.mediaId,
                            isPlaying = playerState.isPlaying &&
                                playerState.current?.mediaId == song.mediaId,
                            onClick = {
                                playerController.playQueue(
                                    songs.map { it.toMediaItem() },
                                    startIndex = index
                                )
                            }
                        )
                    }
                }
            }
        }
    }

}
