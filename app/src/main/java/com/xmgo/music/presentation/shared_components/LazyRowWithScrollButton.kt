package com.xmgo.music.presentation.shared_components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import com.xmgo.music.R
import kotlinx.coroutines.launch

@Composable
fun <T> LazyRowWithScrollButton(
    modifier: Modifier = Modifier,
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T) -> Unit
) {
    val state = rememberLazyListState()

    Box {
        LazyRow(
            state = state,
            modifier = modifier
        ) {
            items(
                items = items,
                key = key
            ) { type ->
                content(type)
            }
        }

        ScrollToEndButton(
            state = state,
            lastIndex = items.lastIndex,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun ScrollToEndButton(
    state: LazyListState,
    lastIndex: Int,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val canScrollForward by remember(state) { derivedStateOf { state.canScrollForward } }

    var hasAppeared by remember { mutableStateOf(false) }
    LaunchedEffect(canScrollForward) {
        if (canScrollForward) hasAppeared = true
    }

    val slideSpec = tween<IntOffset>(durationMillis = 180, easing = FastOutSlowInEasing)
    val fadeSpec = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
    val enter = if (hasAppeared) {
        slideInHorizontally(slideSpec) { it } + fadeIn(fadeSpec)
    } else {
        EnterTransition.None
    }

    AnimatedVisibility(
        visible = canScrollForward,
        modifier = modifier,
        enter = enter,
        exit = slideOutHorizontally(slideSpec) { it } + fadeOut(fadeSpec),
        label = "scrollToEndArrow"
    ) {
        IconButton(
            onClick = {
                scope.launch { state.animateScrollToItem(lastIndex) }
            },
            shapes = IconButtonDefaults.shapes()
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_right),
                contentDescription = null
            )
        }
    }
}
