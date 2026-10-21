package com.xmgo.music.presentation.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xmgo.music.playback.PlayerController
import java.util.Locale

private val SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private val SLEEP_MINUTES = listOf(0, 15, 30, 45, 60, 90)

/** 播放设置底部面板：倍速 + 睡眠定时（与设置页 PlaybackSettingsScreen 同逻辑）。 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSettingsSheet(
    playerController: PlayerController,
    onDismiss: () -> Unit
) {
    val speed by playerController.speed.collectAsState()
    val sleepRemainingMs by playerController.sleepRemainingMs.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "播放倍速",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SPEEDS.forEach { value ->
                    FilterChip(
                        selected = speed == value,
                        onClick = { playerController.setSpeed(value) },
                        label = {
                            Text(
                                text = if (value == value.toInt().toFloat()) {
                                    "${value.toInt()}x"
                                } else {
                                    String.format(Locale.US, "%.2gx", value)
                                }
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = "睡眠定时",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (sleepRemainingMs > 0) {
                    Text(
                        text = "剩余 " + formatRemaining(sleepRemainingMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SLEEP_MINUTES.forEach { minutes ->
                    val selected =
                        if (minutes == 0) sleepRemainingMs <= 0 else false
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (minutes == 0) {
                                playerController.cancelSleepTimer()
                            } else {
                                playerController.startSleepTimer(minutes * 60_000L)
                            }
                        },
                        label = { Text(if (minutes == 0) "关闭" else "$minutes 分钟") }
                    )
                }
            }
        }
    }
}

internal fun formatRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}
