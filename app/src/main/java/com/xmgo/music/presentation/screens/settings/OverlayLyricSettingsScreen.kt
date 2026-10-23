package com.xmgo.music.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.xmgo.music.R
import com.xmgo.music.data.datastore.rememberOverlayLyricBgOpacity
import com.xmgo.music.data.datastore.rememberOverlayLyricEnabled
import com.xmgo.music.data.datastore.rememberOverlayLyricFontSize
import com.xmgo.music.data.datastore.rememberOverlayLyricLocked
import com.xmgo.music.data.datastore.rememberOverlayLyricOutline
import com.xmgo.music.data.datastore.rememberOverlayLyricWidth
import com.xmgo.music.overlay.OverlayLyricManager
import com.xmgo.music.presentation.screens.settings.components.SettingsScaffold
import com.xmgo.music.presentation.screens.settings.components.SettingsWithTitle

/** 悬浮歌词设置页（设置主页入口 → 本页）。 */
@Composable
fun OverlayLyricSettingsScreen(
    onNavigateUp: () -> Unit
) {
    SettingsScaffold(
        title = stringResource(R.string.overlay_lyric),
        onNavigateUp = onNavigateUp
    ) {
        OverlayLyricSettingsContent()
    }
}

@Composable
private fun OverlayLyricSettingsContent() {
    var enabled by rememberOverlayLyricEnabled()
    var locked by rememberOverlayLyricLocked()
    var fontSize by rememberOverlayLyricFontSize()
    var widthPct by rememberOverlayLyricWidth()
    var bgOpacity by rememberOverlayLyricBgOpacity()
    var outline by rememberOverlayLyricOutline()
    val context = LocalContext.current

    // 点了开关但尚未去授权：跳系统授权页前不落盘 true（否则没授权开关也显示开），
    // 授权返回后由下方 ON_RESUME 校验，成功才真正打开。
    var awaitingPermission by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        if (awaitingPermission) {
            awaitingPermission = false
            if (OverlayLyricManager.hasOverlayPermission(context)) {
                enabled = true
            }
            // 未授权返回：enabled 从未置 true，开关保持关，静默即可
        } else if (enabled && !OverlayLyricManager.hasOverlayPermission(context)) {
            // 权限被系统收回：把开关同步回真实状态，避免“显示开却不显示悬浮窗”
            enabled = false
        }
        onPauseOrDispose { }
    }

    SettingsWithTitle(title = R.string.overlay_lyric) {
        Card(
            colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.overlay_lyric_enabled),
                description = stringResource(R.string.overlay_lyric_enabled_desc),
                checked = enabled,
                onCheckedChange = { on ->
                    if (on) {
                        if (OverlayLyricManager.hasOverlayPermission(context)) {
                            enabled = true
                        } else {
                            // 无权限：不落盘（开关保持关），先跳系统授权页；
                            // 授权与否由返回后的 ON_RESUME 校验决定
                            awaitingPermission = true
                            OverlayLyricManager.openOverlayPermissionSettings(context)
                        }
                    } else {
                        awaitingPermission = false
                        enabled = false
                    }
                }
            )
            if (enabled) {
                SettingsSwitchRow(
                    title = stringResource(R.string.overlay_lyric_locked),
                    description = stringResource(R.string.overlay_lyric_locked_desc),
                    checked = locked,
                    onCheckedChange = { locked = it }
                )
                SettingsSliderRow(
                    title = stringResource(R.string.overlay_lyric_font_size),
                    value = fontSize.toFloat(),
                    valueRange = 12f..28f,
                    valueText = "$fontSize",
                    onValueChange = { fontSize = it.toInt() }
                )
                SettingsSliderRow(
                    title = stringResource(R.string.overlay_lyric_width),
                    value = widthPct.toFloat(),
                    valueRange = 50f..100f,
                    valueText = "$widthPct%",
                    onValueChange = { widthPct = it.toInt() }
                )
                SettingsSliderRow(
                    title = stringResource(R.string.overlay_lyric_bg_opacity),
                    value = bgOpacity.toFloat(),
                    valueRange = 0f..100f,
                    valueText = "$bgOpacity%",
                    onValueChange = { bgOpacity = it.toInt() }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.overlay_lyric_outline),
                    description = stringResource(R.string.overlay_lyric_outline_desc),
                    checked = outline,
                    onCheckedChange = { outline = it }
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 文字列右侧留白：避免说明文字贴上开关，并约束换行宽度
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
