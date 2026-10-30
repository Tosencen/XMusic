package com.xmgo.music.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.xmgo.music.playback.CloudSourceManager
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xmgo.music.R
import com.xmgo.music.presentation.navigation.Screen
import com.xmgo.music.presentation.screens.settings.components.SettingsScaffold
import com.xmgo.music.presentation.screens.settings.components.SettingsWithTitle
import com.xmgo.music.presentation.screens.settings.components.XmsSettingsItem
import com.xmgo.music.utils.appVersion

@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    val context = LocalContext.current
    var showCloudSheet by remember { mutableStateOf(false) }
    val cloudSources by CloudSourceManager.sources.collectAsState()
    val primary by CloudSourceManager.primary
    val flowMode by CloudSourceManager.flowMode.collectAsState()
    val anyCloudLoggedIn = cloudSources.any { it.loggedIn }
    // 主音源的登录态直接写在「网盘音乐」副标题上，不点进去就知道有没有登
    val primaryState = cloudSources.firstOrNull { it.type == primary }
    val cloudMusicDesc = if (primaryState != null) {
        primaryState.type.displayName + (primaryState.nickname?.let { " · $it" } ?: "")
    } else "未登录网盘音源"

    SettingsScaffold(
        title = stringResource(R.string.settings),
        onNavigateUp = onNavigateUp
    ) {
        SettingsWithTitle(title = R.string.music_library) {
            // XMSLEEP 风格：独立小卡拼接（首 24/6、中 6/6、末 6/24），卡间距 4dp，各带左图标
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                XmsSettingsItem(
                    icon = Icons.Rounded.Folder,
                    title = stringResource(R.string.local_music),
                    topDp = 24.dp,
                    bottomDp = 6.dp,
                    onClick = { onNavigate(Screen.Local) }
                )
                XmsSettingsItem(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.recently_played),
                    topDp = 6.dp,
                    bottomDp = 6.dp,
                    onClick = { onNavigate(Screen.Recent) }
                )
                XmsSettingsItem(
                    icon = Icons.Rounded.Download,
                    title = stringResource(R.string.cached_music),
                    topDp = 6.dp,
                    bottomDp = 24.dp,
                    onClick = { onNavigate(Screen.CachedMusic) }
                )
            }
        }

        // 网盘三件套：去哪听（网盘音乐）→ 用哪个账号（音源管理）→ 怎么听（流畅模式）。
        // 三者都只作用于云盘曲目，拆到「音乐库」里会让「省流量」看起来跟网盘毫无关系。
        SettingsWithTitle(title = R.string.cloud_resource) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                XmsSettingsItem(
                    icon = Icons.Rounded.Cloud,
                    title = "网盘音乐",
                    description = cloudMusicDesc,
                    topDp = 24.dp,
                    bottomDp = 6.dp,
                    onClick = { onNavigate(Screen.CloudMusic) }
                )
                XmsSettingsItem(
                    icon = Icons.Rounded.ManageAccounts,
                    title = "网盘音源管理",
                    description = "登录网盘账号、切换主音源",
                    topDp = 6.dp,
                    bottomDp = 6.dp,
                    onClick = { showCloudSheet = true },
                    trailing = {
                        // 已登录角标（原先挂在顶部工具栏云图标上，入口合并后搬到这里）
                        if (anyCloudLoggedIn) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                )
                // 流畅模式：云盘曲目走阿里服务端转码流（128kbps MP3，可流式/拖动/硬解/省流量），
                // 代价是二次有损。拿不到转码流会自动回落 FFmpeg 直解，不影响播放。
                XmsSettingsItem(
                    icon = Icons.Rounded.Bolt,
                    title = "流畅模式（省流量）",
                    description = "转码播放：秒开、省流量约 6 成，音质有损；关闭则原码率直解。",
                    topDp = 6.dp,
                    bottomDp = 24.dp,
                    // 左侧留 16dp：条目组件不给文字/trailing 间距，不留会文字贴到开关上
                    trailing = {
                        Box(Modifier.padding(start = 16.dp)) {
                            Switch(checked = flowMode, onCheckedChange = CloudSourceManager::setFlowMode)
                        }
                    }
                )
            }
        }

        SettingsWithTitle(title = R.string.look_and_feel) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                XmsSettingsItem(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.theme),
                    description = stringResource(R.string.look_and_feel_desc),
                    topDp = 24.dp,
                    bottomDp = 6.dp,
                    onClick = { onNavigate(Screen.SettingsLookAndFeel) }
                )
                XmsSettingsItem(
                    icon = Icons.Rounded.Subtitles,
                    title = stringResource(R.string.overlay_lyric),
                    description = stringResource(R.string.overlay_lyric_entry_desc),
                    topDp = 6.dp,
                    bottomDp = 24.dp,
                    onClick = { onNavigate(Screen.OverlayLyricSettings) }
                )
            }
        }

        SettingsWithTitle(title = R.string.plugins) {
            XmsSettingsItem(
                icon = Icons.Rounded.Extension,
                title = stringResource(R.string.plugins),
                description = stringResource(R.string.plugins_desc),
                topDp = 24.dp,
                bottomDp = 24.dp,
                onClick = { onNavigate(Screen.Plugins) }
            )
        }

        SettingsWithTitle(title = R.string.about) {
            XmsSettingsItem(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.app_name),
                description = context.appVersion,
                topDp = 24.dp,
                bottomDp = 24.dp,
                onClick = { onNavigate(Screen.About) }
            )
        }
    }

    // 网盘音源管理弹窗
    if (showCloudSheet) {
        CloudSourceSheet(onDismiss = { showCloudSheet = false })
    }
}

