package com.xmgo.music.playback

import androidx.compose.runtime.mutableFloatStateOf

/** 播放弹窗拖拽进度（0=原位，1=到关闭阈值）：scrim 透明度衰减用（scrim 在 Nav 层独立淡入淡出）。 */
object PlayerSheetState {
    val dragProgress = mutableFloatStateOf(0f)
}
