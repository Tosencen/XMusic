package com.xmgo.music.presentation.shared_components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 行内「正在播放」EQ 小图标：3 根粗条上下律动，尺寸与
 * [androidx.compose.material.icons.rounded.GraphicEq] 一致（默认 20dp），可直接顶替。
 *
 * 形态刻意比"逐条 sin 波"收敛。上一版是 4 根 2.5dp 细条 + 0.32~1.0 大幅度起伏，
 * 实拍三帧看着像几根乱跳的棍子。这一版：
 * 1. 只留 **3 根**、加宽到 4dp —— 20dp 方框里才有分量
 * 2. 高度只在 **0.42~1.0** 之间走，不再缩成小短桩
 * 3. `FastOutSlowInEasing` + `RepeatMode.Reverse` 往返，比线性 sin 有顿挫感
 * 4. 三根周期 380/520/450ms 互不成整数倍 → 不会呈现波浪式的整齐起伏
 *
 * 暂停时收到一组**高低错落**的固定轮廓（不是全部等高），仍是个看得懂的 EQ 剪影。
 */
@Composable
fun AnimatedEqIcon(
    isPlaying: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val grow by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "eqIconGrow"
    )
    val transition = rememberInfiniteTransition(label = "eqIcon")
    // 三根条各自的初始高度与往返周期：初始互不相同 + 周期互质 → 天然错开、不像波浪
    // animateFloat 是 @Composable，必须在 Canvas 外调用；State 在绘制阶段读 → 只重绘不重组
    val a0 = barAnim(transition, 0.42f, 380, "eqIconBar0")
    val a1 = barAnim(transition, 0.92f, 520, "eqIconBar1")
    val a2 = barAnim(transition, 0.62f, 450, "eqIconBar2")
    val paused = floatArrayOf(0.42f, 0.92f, 0.62f)
    val anims = floatArrayOf(a0.value, a1.value, a2.value)

    Canvas(modifier = modifier) {
        val barCount = 3
        val w = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val totalW = w * barCount + gap * (barCount - 1)
        val startX = (size.width - totalW) / 2f
        for (i in 0 until barCount) {
            // 停在初始高度 ↔ 跟随动画，由 grow 混合
            val frac = paused[i] + (anims[i] - paused[i]) * grow
            val h = size.height * frac
            drawRoundRect(
                color = tint,
                topLeft = Offset(startX + i * (w + gap), size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f)
            )
        }
    }
}

@Composable
private fun barAnim(
    transition: androidx.compose.animation.core.InfiniteTransition,
    from: Float,
    periodMs: Int,
    label: String
): androidx.compose.runtime.State<Float> = transition.animateFloat(
    initialValue = from,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
        tween(periodMs, easing = FastOutSlowInEasing),
        RepeatMode.Reverse
    ),
    label = label
)
