package com.xmgo.music.presentation.shared_components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 把 media3 的 buffering 信号转成「该显示加载态了吗」，带 [delayMs] 滞后。
 *
 * 为什么需要滞后：`Player.STATE_BUFFERING` 在**预缓冲**时也会短暂为真，
 * 一变就切加载态的话，播放过程中按钮会不时闪一下转圈，比不显示还烦。
 * 短于 [delayMs] 的抖动直接忽略；一旦真的进入加载，按需求立刻消失（不做淡出）。
 */
@Composable
fun rememberDelayedLoading(buffering: Boolean, delayMs: Long = 300L): Boolean {
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(buffering) {
        if (buffering) {
            delay(delayMs)
            showLoading = true
        } else {
            showLoading = false
        }
    }
    return showLoading
}
