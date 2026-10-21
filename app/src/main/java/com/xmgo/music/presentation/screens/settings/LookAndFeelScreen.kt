package com.xmgo.music.presentation.screens.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xmgo.music.R
import com.xmgo.music.data.datastore.rememberAppTheme
import com.xmgo.music.data.datastore.rememberMonetCover
import com.xmgo.music.data.datastore.rememberPaletteStyle
import com.xmgo.music.data.datastore.rememberUseSystemFont
import com.xmgo.music.presentation.screens.settings.components.FontItem
import com.xmgo.music.presentation.screens.settings.components.FontSelector
import com.xmgo.music.presentation.screens.settings.components.PaletteSelector
import com.xmgo.music.presentation.screens.settings.components.SettingsWithTitle
import com.xmgo.music.presentation.screens.settings.components.ThemeItem
import com.xmgo.music.presentation.screens.settings.components.ThemeSelector
import com.xmgo.music.presentation.screens.settings.components.warmPalettePreviewColors
import com.xmgo.music.presentation.shared_components.LazyRowWithScrollButton
import com.xmgo.music.presentation.theme.NunitoFontFamily
import com.xmgo.music.utils.XmgoPaletteStyle
import com.xmgo.music.utils.XmgoTheme
import com.xmgo.music.utils.systemDarkColorScheme
import com.xmgo.music.utils.systemLightColorScheme

@Composable
fun SettingsLookAndFeel(
    onNavigateUp: () -> Unit
) {
    com.xmgo.music.presentation.screens.settings.components.SettingsScaffold(
        title = stringResource(R.string.look_and_feel),
        onNavigateUp = onNavigateUp
    ) {
        SettingsLookAndFeelContent()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsLookAndFeelContent() {
    var theme by rememberAppTheme()
    var paletteStyle by rememberPaletteStyle()
    var monetCover by rememberMonetCover()
    var useSystemFont by rememberUseSystemFont()

    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()

    val lightScheme = remember(context) { context.systemLightColorScheme() }
    val darkScheme = remember(context) { context.systemDarkColorScheme() }

    val systemLabel = stringResource(R.string.theme_system)
    val darkLabel = stringResource(R.string.theme_dark)
    val lightLabel = stringResource(R.string.theme_light)
    val amoledLabel = stringResource(R.string.theme_amoled)

    val themeItems = remember(
        theme, systemInDark, lightScheme, darkScheme,
        systemLabel, darkLabel, lightLabel, amoledLabel
    ) {
        listOf(
            ThemeItem(
                key = XmgoTheme.SYSTEM,
                onClick = { theme = XmgoTheme.SYSTEM },
                backgroundColor = if (systemInDark) darkScheme.background else lightScheme.background,
                text = systemLabel,
                isSelected = theme == XmgoTheme.SYSTEM,
                icon = R.drawable.system_theme,
                iconTint = if (systemInDark) darkScheme.onBackground else lightScheme.onBackground
            ),
            ThemeItem(
                key = XmgoTheme.DARK,
                onClick = { theme = XmgoTheme.DARK },
                backgroundColor = darkScheme.background,
                text = darkLabel,
                isSelected = theme == XmgoTheme.DARK,
                icon = R.drawable.dark_mode,
                iconTint = darkScheme.onBackground
            ),
            ThemeItem(
                key = XmgoTheme.LIGHT,
                onClick = { theme = XmgoTheme.LIGHT },
                backgroundColor = lightScheme.background,
                text = lightLabel,
                isSelected = theme == XmgoTheme.LIGHT,
                icon = R.drawable.light_mode,
                iconTint = lightScheme.onBackground
            ),
            ThemeItem(
                key = XmgoTheme.AMOLED,
                onClick = { theme = XmgoTheme.AMOLED },
                backgroundColor = Color.Black,
                text = amoledLabel,
                isSelected = theme == XmgoTheme.AMOLED,
                icon = R.drawable.amoled,
                iconTint = Color.White
            )
        )
    }

    val fontItems = remember(useSystemFont) {
        listOf(
            FontItem(
                key = "default",
                onClick = { useSystemFont = false },
                labelRes = R.string.font_default,
                isSelected = !useSystemFont,
                fontFamily = NunitoFontFamily
            ),
            FontItem(
                key = "system",
                onClick = { useSystemFont = true },
                labelRes = R.string.font_system,
                isSelected = useSystemFont,
                fontFamily = null
            )
        )
    }

    val seedColor = MaterialTheme.colorScheme.primary
    val isDark = XmgoTheme.isDark(theme, systemInDark)
    val isAmoled = XmgoTheme.isAmoled(theme)
    LaunchedEffect(seedColor, isDark, isAmoled) {
        warmPalettePreviewColors(
            seed = seedColor,
            isDark = isDark,
            isAmoled = isAmoled
        )
    }

    Column {
        SettingsWithTitle(title = R.string.theme) {
            Card(
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                LazyRowWithScrollButton(items = themeItems, key = { it.key }) { item ->
                    ThemeSelector(item)
                }
            }
        }

        SettingsWithTitle(title = R.string.palette) {
            val isDark = XmgoTheme.isDark(theme, systemInDark)
            val isAmoled = XmgoTheme.isAmoled(theme)

            // XMSLEEP 配色模块风格：无外层卡片，居中 FlowRow 色块容器（一行4个、共2行）
            // 随封面取色无独立开关：选莫奈取色即开启，选其它配色自动关闭
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 4
            ) {
                XmgoPaletteStyle.ALL.forEach { palette ->
                    PaletteSelector(
                        isSelected = palette == paletteStyle,
                        paletteStyle = palette,
                        isDark = isDark,
                        isAmoled = isAmoled,
                        onSelectNewPalette = {
                            paletteStyle = palette
                            monetCover = palette == XmgoPaletteStyle.MONET
                        }
                    )
                }
            }
        }

        SettingsWithTitle(title = R.string.font) {
            Card(
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                LazyRowWithScrollButton(items = fontItems, key = { it.key }) { item ->
                    FontSelector(item)
                }
            }
        }
    }
}
