package com.xmgo.music.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.materialkolor.PaletteStyle

val Context.appVersion: String
    get() = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: ""
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

@Composable
fun anyDarkColorScheme(): ColorScheme = LocalContext.current.systemDarkColorScheme()

fun Context.systemLightColorScheme(): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(this)
    } else {
        lightColorScheme()
    }

fun Context.systemDarkColorScheme(): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(this)
    } else {
        darkColorScheme()
    }

fun String.toPaletteStyle(): PaletteStyle = when (this) {
    // 莫奈：风格取 Fidelity（贴近种子色），seed 由 Theme 层按主题动态提供
    XmgoPaletteStyle.MONET -> PaletteStyle.Fidelity
    XmgoPaletteStyle.EXPRESSIVE -> PaletteStyle.Expressive
    XmgoPaletteStyle.FIDELITY -> PaletteStyle.Fidelity
    XmgoPaletteStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    XmgoPaletteStyle.NEUTRAL -> PaletteStyle.Neutral
    XmgoPaletteStyle.VIBRANT -> PaletteStyle.Vibrant
    XmgoPaletteStyle.MONOCHROME -> PaletteStyle.Monochrome
    XmgoPaletteStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
    else -> throw IllegalArgumentException("Not a valid palette: $this")
}

private val navTween = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = 380,
    easing = FastOutSlowInEasing
)

private val navFadeTween = tween<Float>(
    durationMillis = 380,
    easing = FastOutSlowInEasing
)

val navPushTransform = ContentTransform(
    targetContentEnter = slideInHorizontally(navTween) { it },
    initialContentExit = slideOutHorizontally(navTween) { it / 3 } + fadeOut(navFadeTween)
)

val navPopTransform = ContentTransform(
    targetContentEnter = EnterTransition.None,
    initialContentExit = slideOutHorizontally(navTween) { it }
)

val navPredictivePopTransform: (Int) -> ContentTransform = { _ ->
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = slideOutHorizontally(navTween) { it }
    )
}

/** 全屏播放页：竖向滑入 / 滑出。 */
val navPlayerPushTransform = ContentTransform(
    targetContentEnter = slideInVertically(navTween) { it } + fadeIn(navFadeTween),
    initialContentExit = fadeOut(navFadeTween)
)

val navPlayerPopTransform = ContentTransform(
    targetContentEnter = EnterTransition.None,
    initialContentExit = slideOutVertically(navTween) { it } + fadeOut(navFadeTween)
)

fun NavBackStack<NavKey>.navigateBack() {
    if (size == 1) return
    removeLastOrNull()
}
