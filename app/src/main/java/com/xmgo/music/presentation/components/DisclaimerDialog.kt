package com.xmgo.music.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 免责声明正文（与 README 首段同源）。 */
private val disclaimerParagraphs = listOf(
    "本项目仅用于个人学习与练习。",
    "本项目由一个播放器外壳和一套插件系统组成，不提供在线音源，也不分发受版权保护的媒体。",
    "插件作者与用户需自行遵守服务条款及当地法律。",
    "用户通过插件获取、播放内容所产生的任何责任与后果，由插件作者与用户自行承担，与本应用无关。"
)

@Composable
private fun DisclaimerBody() {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        disclaimerParagraphs.forEach { paragraph ->
            Text(
                text = paragraph,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 查看版：关于页 / 插件管理页入口，点「我知道了」或外部关闭。 */
@Composable
fun DisclaimerDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("免责声明") },
        text = { DisclaimerBody() },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        }
    )
}

/**
 * 首次安装启动强制版：同意后才进入应用，点「不同意并退出」关闭 App；
 * 不可点外部取消（onDismissRequest 空实现）。
 */
@Composable
fun FirstRunDisclaimerDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("免责声明") },
        text = { DisclaimerBody() },
        confirmButton = {
            TextButton(onClick = onAgree) { Text("同意并进入") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("不同意并退出") }
        }
    )
}
