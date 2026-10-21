@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.xmgo.music.presentation.shared_components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 播放解析中的加载提示：无遮罩、不拦截点击，屏幕中央显示带圆形底衬的
 * 最新 MD3（Material 3 Expressive）波浪加载指示器。
 */
@Composable
fun ResolvingIndicator(visible: Boolean) {
    if (!visible) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircleBackdropWavyIndicator()
    }
}

/**
 * 统一的居中加载指示器：带圆形底衬的 MD3 波浪加载器。
 * 页面级 loading 态（首页/详情/搜索等）一律用它，不再用裸 CircularProgressIndicator。
 */
@Composable
fun CircleBackdropWavyIndicator(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            Box(modifier = Modifier.padding(20.dp)) {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
