package com.xmgo.music.update

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * 自更新全局对话框：观察 AppUpdater.pendingUpdate。
 * 确认 → 下载（进度）→ 完成即消费并调起系统安装页；下载失败 Toast 并停留可重试。
 */
@Composable
fun AppUpdateDialogHost() {
    val updater: AppUpdater = remember { KoinPlatform.getKoin().get() }
    val info by updater.pendingUpdate.collectAsState()
    val update = info ?: return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) updater.consume() },
        title = { Text("发现新版本 ${update.version}") },
        text = {
            Column {
                Text(
                    text = update.notes.ifBlank { "版本 ${update.version} 已发布。" }
                        .take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis
                )
                if (downloading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = {
                            val (done, total) = progress ?: (0L to 0L)
                            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = progress?.let { (done, total) ->
                            val mb = done / 1048576.0
                            if (total > 0) "%.1f / %.1f MB".format(mb, total / 1048576.0)
                            else "%.1f MB".format(mb)
                        } ?: "准备下载…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    scope.launch {
                        runCatching { updater.download(update) { d, t -> progress = d to t } }
                            .onSuccess { file ->
                                updater.consume()
                                runCatching { updater.install(file) }.onFailure {
                                    Toast.makeText(
                                        context, "无法启动安装：${it.message}", Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            .onFailure {
                                downloading = false
                                progress = null
                                Toast.makeText(
                                    context, "下载失败：${it.message}", Toast.LENGTH_LONG
                                ).show()
                            }
                    }
                }
            ) {
                Text(if (downloading) "下载中…" else "下载更新")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { updater.consume() }
            ) { Text("取消") }
        }
    )
}
