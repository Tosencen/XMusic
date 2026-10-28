package com.xmgo.music.presentation.shared_components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 骨架屏呼吸脉冲透明度（0.35 ↔ 0.85，约 1.1s 一个来回，循环反向）。
 * 返回值在组合期读取，所有调用方共享同一动画即可保持同步呼吸。
 */
@Composable
fun rememberSkeletonPulse(): Float {
    val transition = rememberInfiniteTransition(label = "skeletonPulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonPulseAlpha"
    )
    return alpha
}

/**
 * 骨架占位块：surfaceVariant 底色 + 呼吸脉冲。
 * 用法：[alpha] 传 [rememberSkeletonPulse] 的返回值，整屏占位块共享一次动画。
 */
@Composable
fun SkeletonBlock(
    alpha: Float,
    modifier: Modifier = Modifier,
    shape: CornerBasedShape = RoundedCornerShape(8.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .graphicsLayer { this.alpha = alpha }
    )
}
