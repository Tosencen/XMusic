package com.xmgo.music.presentation.shared_components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 顺序 / 随机播放切换按钮（列表页与队列弹窗共用）。
 * 列表场景只提供这两种模式；单曲循环仅在全屏播放页可选。
 */
@Composable
fun PlayModeToggle(
    shuffleEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (shuffleEnabled) Icons.Rounded.Shuffle else Icons.Rounded.Sort,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (shuffleEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (shuffleEnabled) "随机播放" else "顺序播放",
            style = MaterialTheme.typography.labelLarge,
            color = if (shuffleEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 紧凑图标版（TopAppBar actions / 队列弹窗头部 / 歌单详情操作行用）。
 * containerColor 默认透明；传入（如 secondaryContainer）时绘制圆形底色。
 */
@Composable
fun PlayModeToggleIcon(
    shuffleEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(containerColor = containerColor)
    ) {
        Icon(
            imageVector = if (shuffleEnabled) Icons.Rounded.Shuffle else Icons.Rounded.Sort,
            contentDescription = if (shuffleEnabled) "随机播放，点击切换顺序播放" else "顺序播放，点击切换随机播放",
            tint = if (shuffleEnabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
