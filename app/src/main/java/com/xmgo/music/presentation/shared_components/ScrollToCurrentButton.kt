package com.xmgo.music.presentation.shared_components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 定位到正在播放歌曲的悬浮按钮：与 [ScrollToTopButton] 同尺寸同形态，竖排在它上方。
 *
 * 可见条件是三条**与**（缺一不可）：
 * 1. [visible]（调用方控制）
 * 2. [currentIndex] >= 0 —— 正在播放的歌确实在本列表里
 * 3. 该行**中心点**不在视口内 —— 已经看得见就没必要定位
 *
 * 第 3 条用「行中心在视口内」而不是「露出就算」：滚到边缘只露两像素时用户根本认不出
 * 是哪首，这时候按钮却消失了会很怪；反之按露出判断还会在边界上来回闪。
 *
 * 点击用 [androidx.compose.foundation.lazy.LazyListState.scrollToItem] **瞬间**定位，
 * 不用 animateScrollToItem：跨几百项的补间既慢又容易跳变，这里要的是「到那儿」，
 * 不是「看它滚过去」。
 *
 * [onLocated] 在滚到位之后回调，调用方拿去给那一行做高亮闪烁（见 [SongRow.highlightPulse]）。
 */
@Composable
fun ScrollToCurrentButton(
    listState: LazyListState,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onLocated: () -> Unit = {}
) {
    val inViewport by remember(currentIndex, listState) {
        derivedStateOf {
            if (currentIndex < 0) {
                true
            } else {
                val info = listState.layoutInfo
                val top = info.viewportStartOffset.toFloat()
                val bottom = info.viewportEndOffset.toFloat()
                info.visibleItemsInfo.any { item ->
                    item.index == currentIndex &&
                        (item.offset + item.size / 2f) in top..bottom
                }
            }
        }
    }
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = visible && currentIndex >= 0 && !inViewport,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
        modifier = modifier
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(44.dp)
                .shadow(6.dp, CircleShape)
                .clickable {
                    if (currentIndex < 0) return@clickable
                    scope.launch {
                        listState.scrollToItem(currentIndex)
                        onLocated()
                    }
                }
        ) {
            Icon(
                imageVector = Icons.Rounded.MyLocation,
                contentDescription = "定位到正在播放",
                modifier = Modifier.size(44.dp).padding(10.dp)
            )
        }
    }
}
