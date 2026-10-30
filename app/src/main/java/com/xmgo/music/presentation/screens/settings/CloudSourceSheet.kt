package com.xmgo.music.presentation.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmgo.music.playback.CloudSourceManager
import com.xmgo.music.playback.CloudSourceState
import com.xmgo.music.playback.CloudSourceType

/**
 * 网盘音源管理弹窗：每源一行（登录态/设主音源/退出），底部实时显示当前主音源。
 * P0 登录走"粘贴 refresh_token"（OAuth 跳转待官方 appKey 申请后升级）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSourceSheet(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sources by CloudSourceManager.sources.collectAsState()
    val primary by CloudSourceManager.primary
    val scope = rememberCoroutineScope()
    var loginTarget by remember { mutableStateOf<CloudSourceType?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // 标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "网盘音源管理",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            sources.forEach { source ->
                SourceCard(
                    source = source,
                    isPrimary = primary == source.type,
                    onSetPrimary = { CloudSourceManager.setPrimary(source.type) },
                    onLogin = { loginTarget = source.type },
                    onLogout = { CloudSourceManager.logout(source.type) }
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = if (primary != null) "当前主音源：${primary!!.displayName}" else "未设置主音源",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // 网盘登录（原生二维码=借官方网页机制；扫码成功→轻提示+关弹窗）
    loginTarget?.let { type ->
        CloudWebLoginSheet(
            type = type,
            onDismiss = { loginTarget = null },
            onSuccess = {
                loginTarget = null
                android.widget.Toast.makeText(
                    context,
                    "已登录${type.displayName}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        )
    }
}

@Composable
private fun SourceCard(
    source: CloudSourceState,
    isPrimary: Boolean,
    onSetPrimary: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val accent = when (source.type) {
        CloudSourceType.ALIYUN -> Color(0xFFFF6A00)
        CloudSourceType.QUARK -> Color(0xFF2E6BFF)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (isPrimary) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape)
                else Modifier
            )
            .background(
                if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 源图标（品牌色圆点）
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = source.type.displayName.first().toString(),
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = source.type.displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (source.loggedIn) "已登录" + (source.nickname?.let { " · $it" } ?: "")
                    else "未登录",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (source.loggedIn) {
                if (isPrimary) {
                    Icon(
                        imageVector = Icons.Rounded.Done,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "主音源",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    FilledTonalButton(onClick = onSetPrimary) { Text("设为主音源", fontSize = 13.sp) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLogout) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            } else {
                FilledTonalButton(onClick = onLogin) { Text("登录", fontSize = 13.sp) }
            }
        }
    }
}

