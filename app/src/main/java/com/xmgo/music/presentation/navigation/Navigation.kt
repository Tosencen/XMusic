package com.xmgo.music.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.xmgo.music.playback.PlayerController
import com.xmgo.music.presentation.screens.about.AboutScreen
import com.xmgo.music.presentation.screens.home.HomeScreen
import com.xmgo.music.presentation.screens.player.PlayerScreen
import com.xmgo.music.presentation.screens.search.SearchScreen
import com.xmgo.music.presentation.screens.settings.SettingsLookAndFeel
import com.xmgo.music.presentation.screens.settings.SettingsScreen
import com.xmgo.music.presentation.shared_components.MiniPlayer
import com.xmgo.music.utils.navPopTransform
import com.xmgo.music.utils.navPredictivePopTransform
import com.xmgo.music.utils.navPushTransform
import com.xmgo.music.utils.navigateBack
import org.koin.compose.getKoin

private val screenTransitionMetadata =
    NavDisplay.transitionSpec { navPushTransform } +
        NavDisplay.popTransitionSpec { navPopTransform } +
        NavDisplay.predictivePopTransitionSpec { swipeEdge -> navPredictivePopTransform(swipeEdge) }

@Composable
fun Nav() {
    // 不做 savedState 跨进程恢复：进程重建后总是回首页，避免恢复栈缺 Home 导致 back 早退
    val backStack = remember { NavBackStack<NavKey>(Screen.Home) }
    val playerController: PlayerController = getKoin().get()
    val cachedMusicStore: com.xmgo.music.data.cache.CachedMusicStore = getKoin().get()
    val pluginManager: com.xmgo.music.plugin.PluginManager = getKoin().get()
    // 缓存结果全局提示（开始/成功/失败经 CachedMusicStore.events 广播）
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        cachedMusicStore.events.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 首启音源配置（复刻 qplayer）：无插件且未选「只用本地」时弹出，装好/确认后不再弹
    var setupAck by com.xmgo.music.data.datastore.rememberPluginSetupAck()
    var setupHandled by remember { androidx.compose.runtime.mutableStateOf(false) }
    if (!setupAck && !setupHandled && pluginManager.plugins.isEmpty()) {
        com.xmgo.music.presentation.shared_components.PluginSetupDialog(
            onDone = {
                setupAck = true
                setupHandled = true
            }
        )
    }
    // 媒体通知权限（播放服务通知栏控制）：等首启音源配置处理完再请求，
    // 避免与配置弹窗同屏叠加（错峰，一次只打扰一件事）
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    androidx.compose.runtime.LaunchedEffect(setupAck) {
        if (setupAck &&
            android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.navigateBack() },
            modifier = Modifier
                .fillMaxSize(),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<Screen.Home>(
                    metadata = screenTransitionMetadata
                ) {
                    HomeScreen(
                        onNavigateToSearch = {
                            backStack.navigate(Screen.Search)
                        },
                        onNavigateToFavorites = {
                            backStack.navigate(Screen.Favorites)
                        },
                        onNavigateToSettings = {
                            backStack.navigate(Screen.Settings)
                        },
                        onNavigateToDetail = { kind, sourceId, id, title ->
                            backStack.navigate(Screen.OnlineDetail(kind, sourceId, id, title))
                        }
                    )
                }

                entry<Screen.Local>(
                    metadata = screenTransitionMetadata
                ) {
                    com.xmgo.music.presentation.screens.local.LocalScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.OnlineDetail>(
                    metadata = screenTransitionMetadata
                ) { entry ->
                    com.xmgo.music.presentation.screens.online.OnlineDetailScreen(
                        kind = entry.kind,
                        sourceId = entry.sourceId,
                        id = entry.id,
                        onNavigateUp = backStack::navigateBack,
                        onNavigateToDetail = { kind, sourceId, id, title ->
                            backStack.navigate(Screen.OnlineDetail(kind, sourceId, id, title))
                        }
                    )
                }

                entry<Screen.Recent>(
                    metadata = screenTransitionMetadata
                ) {
                    com.xmgo.music.presentation.screens.recent.RecentScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.Favorites>(
                    metadata = screenTransitionMetadata
                ) {
                    com.xmgo.music.presentation.screens.favorites.FavoritesScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.CachedMusic>(
                    metadata = screenTransitionMetadata
                ) {
                    com.xmgo.music.presentation.screens.cached.CachedMusicScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.Search>(
                    metadata = screenTransitionMetadata
                ) {
                    SearchScreen(
                        onNavigateUp = backStack::navigateBack,
                        onNavigateToDetail = { kind, sourceId, id, title ->
                            backStack.navigate(Screen.OnlineDetail(kind, sourceId, id, title))
                        }
                    )
                }

                entry<Screen.Settings>(
                    metadata = screenTransitionMetadata
                ) {
                    SettingsScreen(
                        onNavigateUp = backStack::navigateBack,
                        onNavigate = backStack::navigate
                    )
                }

                entry<Screen.SettingsLookAndFeel>(
                    metadata = screenTransitionMetadata
                ) {
                    SettingsLookAndFeel(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.OverlayLyricSettings>(
                    metadata = screenTransitionMetadata
                ) {
                    com.xmgo.music.presentation.screens.settings.OverlayLyricSettingsScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.Plugins>(
                    metadata = screenTransitionMetadata
                ) {
                    com.xmgo.music.presentation.screens.plugins.PluginsScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }

                entry<Screen.About>(
                    metadata = screenTransitionMetadata
                ) {
                    AboutScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }
            }
        )

        // 播放页真底部弹窗：弹层形态（下层真实渲染、缝隙实时透出），
        // 不走 Nav entry——弹窗与页面共存，关闭无重组空档
        var showPlayer by remember { mutableStateOf(false) }

        // 悬浮播放模块：仅首页与音乐列表页显示（搜索/设置等次级页面不显示）
        val showMiniPlayer = when (backStack.lastOrNull()) {
            Screen.Home,
            Screen.Local,
            Screen.Recent,
            Screen.Favorites,
            Screen.CachedMusic,
            Screen.Search,
            is Screen.OnlineDetail -> true

            else -> false
        }
        if (showMiniPlayer) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp)
                    .navigationBarsPadding()
            ) {
                MiniPlayer(
                    playerController = playerController,
                    onOpenPlayer = { showPlayer = true }
                )
            }
        }
        // 播放页 scrim：独立淡入淡出（不跟弹窗滑动——标准底部弹窗行为），
        // 拖拽时透明度随 PlayerSheetState.dragProgress 衰减到 0
        androidx.compose.animation.AnimatedVisibility(
            visible = showPlayer,
            enter = androidx.compose.animation.fadeIn(
                androidx.compose.animation.core.tween(380)
            ),
            exit = androidx.compose.animation.fadeOut(
                androidx.compose.animation.core.tween(380)
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            val drag by com.xmgo.music.playback.PlayerSheetState.dragProgress
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = 0.5f * (1f - drag.coerceIn(0f, 1f)))
                    )
            )
        }
        // 播放页弹层（最上层）：从下滑入/滑出，关闭时下层（首页等）始终真实可见
        val sheetTween = androidx.compose.animation.core.tween<Float>(380)
        val sheetOffsetTween = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(380)
        androidx.compose.animation.AnimatedVisibility(
            visible = showPlayer,
            enter = androidx.compose.animation.slideInVertically(
                animationSpec = sheetOffsetTween
            ) { it } + androidx.compose.animation.fadeIn(sheetTween),
            exit = androidx.compose.animation.slideOutVertically(
                animationSpec = sheetOffsetTween
            ) { it } + androidx.compose.animation.fadeOut(sheetTween)
        ) {
            PlayerScreen(
                onNavigateUp = { showPlayer = false }
            )
        }
    }
}

fun NavBackStack<NavKey>.navigate(screen: NavKey) {
    remove(screen)
    add(screen)
}
