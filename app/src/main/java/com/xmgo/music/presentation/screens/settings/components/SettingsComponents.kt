@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.xmgo.music.presentation.screens.settings.components

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xmgo.music.presentation.theme.NunitoFontFamily
import com.xmgo.music.utils.XmgoPaletteStyle
import com.xmgo.music.utils.toPaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SelectorSurface(
    onClick: () -> Unit,
    content: @Composable (ColumnScope.() -> Unit)
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        content = content
    )
}

@Composable
fun ThemeSelector(theme: ThemeItem) {
    val borderColor by animateColorAsState(
        targetValue = if (theme.isSelected) MaterialTheme.colorScheme.secondary else Color.Transparent,
        label = "themeBorder"
    )

    SelectorSurface(onClick = theme.onClick) {
        Box(
            modifier = Modifier
                .padding(10.dp)
                .size(50.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .border(
                    width = 2.dp,
                    color = borderColor,
                    shape = MaterialShapes.Cookie9Sided.toShape()
                )
                .background(theme.backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(theme.icon),
                contentDescription = null,
                tint = theme.iconTint
            )
        }
        Text(
            text = theme.text,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun PaletteSelector(
    isSelected: Boolean,
    paletteStyle: String,
    isDark: Boolean,
    isAmoled: Boolean,
    onSelectNewPalette: () -> Unit
) {
    val previewColors = palettePreviewColors(
        paletteStyle = paletteStyle,
        isDark = isDark,
        isAmoled = isAmoled
    )

    // XMSLEEP 配色模块风格：无外层卡片，64dp 圆角色块容器 + 内圈 48dp 圆形色带 + 选中打勾
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelectNewPalette)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
            ) {
                previewColors.forEach { color ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(color)
                    )
                }
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (paletteStyle == XmgoPaletteStyle.MONET) "莫奈取色" else paletteStyle,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp)
        )
    }
}

private val palettePreviewCache = mutableMapOf<String, List<Color>>()

private val palettePreviewVersion = mutableStateOf(0)

private fun palettePreviewCacheKey(
    seed: Color,
    isDark: Boolean,
    isAmoled: Boolean,
    style: String
): String = "${seed.toArgb()}|$isDark|$isAmoled|$style"

suspend fun warmPalettePreviewColors(
    seed: Color,
    isDark: Boolean,
    isAmoled: Boolean
) {
    val missing = XmgoPaletteStyle.ALL.mapNotNull { style ->
        val key = palettePreviewCacheKey(seed, isDark, isAmoled, style)
        if (palettePreviewCache.containsKey(key)) null else (style to key)
    }
    if (missing.isEmpty()) return
    val computed = withContext(Dispatchers.Default) {
        missing.map { (style, key) ->
            val scheme = dynamicColorScheme(
                seedColor = seed,
                isDark = isDark,
                isAmoled = isAmoled,
                style = style.toPaletteStyle(),
                specVersion = ColorSpec.SpecVersion.SPEC_2025
            )
            key to listOf(
                scheme.primary,
                scheme.secondary,
                scheme.tertiary,
                scheme.primaryContainer,
                scheme.secondaryContainer
            )
        }
    }
    computed.forEach { (k, v) -> palettePreviewCache[k] = v }
    palettePreviewVersion.value++
}

@Composable
fun palettePreviewColors(
    paletteStyle: String,
    isDark: Boolean,
    isAmoled: Boolean
): List<Color> {
    val seed = MaterialTheme.colorScheme.primary
    val key = remember(paletteStyle, isDark, isAmoled, seed) {
        palettePreviewCacheKey(seed, isDark, isAmoled, paletteStyle)
    }
    palettePreviewVersion.value
    return palettePreviewCache[key] ?: listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer
    )
}

@Composable
fun FontSelector(fontItem: FontItem) {
    val borderColor by animateColorAsState(
        targetValue = if (fontItem.isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "fontBorder"
    )

    SelectorSurface(onClick = fontItem.onClick) {
        Box(
            modifier = Modifier
                .padding(10.dp)
                .size(50.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .border(
                    width = 2.dp,
                    color = borderColor,
                    shape = MaterialShapes.Cookie9Sided.toShape()
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Tt",
                fontFamily = fontItem.fontFamily,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium
            )
        }
        Text(
            text = stringResource(fontItem.labelRes),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun SettingsWithTitle(
    @StringRes title: Int,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLargeEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        )
        content()
    }
}

/** XMSLEEP 风格条目左侧图标徽章：40dp 圆角方块 primary 10% 底 + 24dp 图标。 */
@Composable
fun SettingsIconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * XMSLEEP 风格设置条目卡：独立小卡（surfaceVariant 30% 背景、无阴影），
 * 行内左图标徽章 + 标题/描述（titleMedium / bodySmall onSurfaceVariant），行距 h20/v16。
 * 组内多条通过 topDp/bottomDp 形成「首条 24/6、中间 6/6、末条 6/24、单条 24/24」拼接样式。
 */
@Composable
fun XmsSettingsItem(
    icon: ImageVector,
    title: String,
    description: String? = null,
    topDp: Dp = 6.dp,
    bottomDp: Dp = 6.dp,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(
        topStart = topDp,
        topEnd = topDp,
        bottomStart = bottomDp,
        bottomEnd = bottomDp
    )
    val itemModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
    val colors = CardDefaults.cardColors(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    )
    val rowContent: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            SettingsIconBadge(icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            trailing?.invoke()
        }
    }
    if (onClick != null) {
        Card(
            shape = shape,
            colors = colors,
            modifier = itemModifier,
            onClick = onClick,
            content = { rowContent() }
        )
    } else {
        Card(
            shape = shape,
            colors = colors,
            modifier = itemModifier,
            content = { rowContent() }
        )
    }
}

data class ThemeItem(
    val key: String,
    val onClick: () -> Unit,
    val backgroundColor: Color,
    val text: String,
    val isSelected: Boolean,
    val icon: Int,
    val iconTint: Color
)

data class FontItem(
    val key: String,
    val onClick: () -> Unit,
    val labelRes: Int,
    val isSelected: Boolean,
    val fontFamily: FontFamily?
)
