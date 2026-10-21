package com.xmgo.music.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.xmgo.music.utils.navPlayerPopTransform
import com.xmgo.music.utils.navPlayerPushTransform
import com.xmgo.music.utils.navPopTransform
import com.xmgo.music.utils.navPredictivePopTransform
import com.xmgo.music.utils.navPushTransform
import com.xmgo.music.utils.navigateBack
import org.koin.compose.getKoin

private val screenTransitionMetadata =
    NavDisplay.transitionSpec { navPushTransform } +
        NavDisplay.popTransitionSpec { navPopTransform } +
        NavDisplay.predictivePopTransitionSpec { swipeEdge -> navPredictivePopTransform(swipeEdge) }

private val playerTransitionMetadata =
    NavDisplay.transitionSpec { navPlayerPushTransform } +
        NavDisplay.popTransitionSpec { navPlayerPopTransform } +
        NavDisplay.predictivePopTransitionSpec { _ -> navPlayerPopTransform }

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
    Box(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.navigateBack() },
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
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

                entry<Screen.Player>(
                    metadata = playerTransitionMetadata
                ) {
                    PlayerScreen(
                        onNavigateUp = backStack::navigateBack
                    )
                }
            }
        )

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
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding()
            ) {
                MiniPlayer(
                    playerController = playerController,
                    onOpenPlayer = { backStack.navigate(Screen.Player) }
                )
            }
        }
    }
}

fun NavBackStack<NavKey>.navigate(screen: NavKey) {
    remove(screen)
    add(screen)
}
