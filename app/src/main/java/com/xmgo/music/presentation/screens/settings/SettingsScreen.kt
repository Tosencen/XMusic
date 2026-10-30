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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.xmgo.music.playback.CloudSourceManager
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
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
    val anyCloudLoggedIn = cloudSources.any { it.loggedIn }

    SettingsScaffold(
        title = stringResource(R.string.settings),
        onNavigateUp = onNavigateUp,
        actions = {
            // 网盘音源管理入口（有可用源时带角标）
            Box {
                IconButton(onClick = { showCloudSheet = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = "网盘音源管理",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (anyCloudLoggedIn) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
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
