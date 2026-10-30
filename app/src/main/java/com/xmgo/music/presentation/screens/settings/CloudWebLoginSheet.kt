package com.xmgo.music.presentation.screens.settings

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xmgo.music.playback.AliDriveApi
import com.xmgo.music.playback.CloudSourceManager
import com.xmgo.music.playback.CloudSourceType
import com.xmgo.music.playback.QrLogin
import kotlinx.coroutines.delay

/**
 * 网盘扫码登录弹层（生成式：QrLogin 调 passport 接口生成二维码，itxve 实证）：
 * 显示原生二维码 → App 扫码确认 → 轮询 → bizExt 解出 refresh_token → 自动登录。
 */
@Composable
fun CloudWebLoginSheet(
    type: CloudSourceType,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    var qrImage by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在生成二维码…") }
    var session by remember { mutableStateOf<QrLogin.QrSession?>(null) }
    var pollTick by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }

    // 生成二维码 + 轮询（pollTick 变化=刷新重启）
    LaunchedEffect(pollTick) {
        qrImage = null
        statusText = "正在生成二维码…"
        val gen = QrLogin.aliGenerate()
        gen.onSuccess { s ->
            session = s
            qrImage = QrLogin.qrBitmap(s.content)
            statusText = "请用阿里云盘 App 扫码登录"
            while (!done) {
                delay(1500)
                val q = QrLogin.aliQuery(s)
                q.onSuccess { (st, token) ->
                    when (st) {
                        QrLogin.Status.SCANED -> statusText = "已扫描，请在手机上确认"
                        QrLogin.Status.CONFIRMED -> {
                            if (token != null) {
                                statusText = "正在登录…"
                                AliDriveApi.refreshAuth(token)
                                    .onSuccess {
                                        CloudSourceManager.onLoggedIn(type, it.nickname, it.refreshToken)
                                        done = true
                                        Toast.makeText(context, "已登录${type.displayName}", Toast.LENGTH_SHORT).show()
                                        onSuccess()
                                    }
                                    .onFailure {
                                        CloudSourceManager.onLoggedIn(type, null, token)
                                        done = true
                                        Toast.makeText(context, "已登录${type.displayName}", Toast.LENGTH_SHORT).show()
                                        onSuccess()
                                    }
                            } else {
                                statusText = "已确认但未获取凭证，请刷新重试"
                            }
                        }
                        QrLogin.Status.EXPIRED, QrLogin.Status.CANCELLED -> {
                            statusText = "二维码已失效，点「刷新」重新生成"
                        }
                        else -> {}
                    }
                }.onFailure { statusText = "网络异常，点「刷新」重试" }
            }
        }.onFailure {
            statusText = "生成失败：${it.message?.take(40)}，点「刷新」重试"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码登录${type.displayName}", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val bmp = qrImage
                if (bmp != null) {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "登录二维码",
                            modifier = Modifier.size(216.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(36.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "打开阿里云盘 App → 扫一扫 → 确认登录",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { pollTick++ }) { Text("刷新") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
