@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.xmgo.music.presentation.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowInsetsControllerCompat
import com.xmgo.music.R
import com.xmgo.music.data.datastore.rememberAppTheme
import com.xmgo.music.data.datastore.rememberMonetCover
import com.xmgo.music.data.datastore.rememberPaletteStyle
import com.xmgo.music.data.datastore.rememberUseSystemFont
import com.xmgo.music.playback.ArtworkSeed
import com.xmgo.music.utils.XmgoPaletteStyle
import com.xmgo.music.utils.XmgoTheme
import com.xmgo.music.utils.anyDarkColorScheme
import com.xmgo.music.utils.toPaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun XmgoTheme(
    content: @Composable () -> Unit,
) {
    val view: android.view.View = LocalView.current
    val theme by rememberAppTheme()
    val useSystemFont by rememberUseSystemFont()
    val paletteStyle by rememberPaletteStyle()
    val monetCover by rememberMonetCover()
    val systemInDark = isSystemInDarkTheme()

    val isDark = XmgoTheme.isDark(theme, systemInDark)
    val isAmoled = XmgoTheme.isAmoled(theme)
    val style = paletteStyle.toPaletteStyle()

    // 莫奈取色：seed 随当前播放封面动态取色（ArtworkSeed），无封面/未起播回退默认 seed
    val artworkSeed by ArtworkSeed.seed.collectAsState()
    val useCoverSeed = paletteStyle == XmgoPaletteStyle.MONET && monetCover && artworkSeed != null
    val seedColor = if (useCoverSeed) {
        artworkSeed!!
    } else {
        anyDarkColorScheme().primary
    }

    // 只保留后台计算一条路径（旧实现 remember 里在主线程先算一遍、LaunchedEffect 又算一遍，
    // 首帧白算一次）；初始值给一个中性默认色，isAmoled 参与 key（切纯黑时主题要重算）
    val schemeState = remember {
        mutableStateOf(
            dynamicColorScheme(
                seedColor = seedColor,
                isDark = isDark,
                isAmoled = isAmoled,
                style = style,
                specVersion = ColorSpec.SpecVersion.SPEC_2025
            )
        )
    }

    LaunchedEffect(seedColor, isDark, isAmoled, style) {
        val newScheme = withContext(Dispatchers.Default) {
            dynamicColorScheme(
                seedColor = seedColor,
                isDark = isDark,
                isAmoled = isAmoled,
                style = style,
                specVersion = ColorSpec.SpecVersion.SPEC_2025
            )
        }
        schemeState.value = newScheme
    }

    if (!view.isInEditMode) {
        LaunchedEffect(isDark) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            window.statusBarColor = Color.Transparent.toArgb()
            WindowInsetsControllerCompat(window, view).also { wic ->
                wic.isAppearanceLightStatusBars = !isDark
                wic.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    SideEffect {
        // 悬浮歌词窗等非 composition 渲染读取当前主题色
        AppThemePalette.scheme = schemeState.value
    }

    MaterialTheme(
        colorScheme = schemeState.value,
        typography = if (useSystemFont) MaterialTheme.typography else NunitoTypography,
        content = content
    )
}

/** 进程内当前主题色（悬浮歌词窗自绘取色用；主线程写，读方容忍可见性）。 */
object AppThemePalette {
    @Volatile
    var scheme: androidx.compose.material3.ColorScheme? = null
}

val NunitoFontFamily = FontFamily(
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold, FontStyle.Normal),
    Font(R.font.nunito_extralight, FontWeight.ExtraLight, FontStyle.Normal)
)

private fun TextStyle.withNunito(weight: FontWeight): TextStyle =
    copy(fontFamily = NunitoFontFamily, fontWeight = weight)

val NunitoTypography = Typography().run {
    copy(
        displayLarge = displayLarge.withNunito(FontWeight.ExtraBold),
        displayMedium = displayMedium.withNunito(FontWeight.ExtraBold),
        displaySmall = displaySmall.withNunito(FontWeight.ExtraBold),

        headlineLarge = headlineLarge.withNunito(FontWeight.ExtraBold),
        headlineMedium = headlineMedium.withNunito(FontWeight.ExtraBold),
        headlineSmall = headlineSmall.withNunito(FontWeight.ExtraBold),

        titleLarge = titleLarge.withNunito(FontWeight.ExtraBold),
        titleMedium = titleMedium.withNunito(FontWeight.ExtraBold),
        titleSmall = titleSmall.withNunito(FontWeight.ExtraBold),

        bodyLarge = bodyLarge.withNunito(FontWeight.SemiBold),
        bodyMedium = bodyMedium.withNunito(FontWeight.SemiBold),
        bodySmall = bodySmall.withNunito(FontWeight.SemiBold),

        labelLarge = labelLarge.withNunito(FontWeight.SemiBold),
        labelMedium = labelMedium.withNunito(FontWeight.SemiBold),
        labelSmall = labelSmall.withNunito(FontWeight.SemiBold),

        displayLargeEmphasized = displayLargeEmphasized.withNunito(FontWeight.ExtraBold),
        displayMediumEmphasized = displayMediumEmphasized.withNunito(FontWeight.ExtraBold),
        displaySmallEmphasized = displaySmallEmphasized.withNunito(FontWeight.ExtraBold),

        headlineLargeEmphasized = headlineLargeEmphasized.withNunito(FontWeight.ExtraBold),
        headlineMediumEmphasized = headlineMediumEmphasized.withNunito(FontWeight.ExtraBold),
        headlineSmallEmphasized = headlineSmallEmphasized.withNunito(FontWeight.ExtraBold),

        titleLargeEmphasized = titleLargeEmphasized.withNunito(FontWeight.ExtraBold),
        titleMediumEmphasized = titleMediumEmphasized.withNunito(FontWeight.ExtraBold),
        titleSmallEmphasized = titleSmallEmphasized.withNunito(FontWeight.ExtraBold),

        bodyLargeEmphasized = bodyLargeEmphasized.withNunito(FontWeight.SemiBold),
        bodyMediumEmphasized = bodyMediumEmphasized.withNunito(FontWeight.SemiBold),
        bodySmallEmphasized = bodySmallEmphasized.withNunito(FontWeight.SemiBold),

        labelLargeEmphasized = labelLargeEmphasized.withNunito(FontWeight.SemiBold),
        labelMediumEmphasized = labelMediumEmphasized.withNunito(FontWeight.SemiBold),
        labelSmallEmphasized = labelSmallEmphasized.withNunito(FontWeight.SemiBold),
    )
}
