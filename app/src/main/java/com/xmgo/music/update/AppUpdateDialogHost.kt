package com.xmgo.music.update

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) updater.consume() },
        title = { Text("发现新版本 ${update.version}") },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    ReleaseNotesText(
                        notes = update.notes.ifBlank { "版本 ${update.version} 已发布。" }
                    )
                }
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
                    downloadJob = scope.launch {
                        try {
                            val file = updater.download(update) { d, t -> progress = d to t }
                            updater.consume()
                            runCatching { updater.install(file) }.onFailure {
                                Toast.makeText(
                                    context, "无法启动安装：${it.message}", Toast.LENGTH_LONG
                                ).show()
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            downloading = false
                            progress = null
                            Toast.makeText(
                                context, "下载失败：${e.message}", Toast.LENGTH_LONG
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
                // 下载中变"取消"：终止协程（传输协作取消点会尽快停止并清理 .part）
                enabled = true,
                onClick = {
                    if (downloading) {
                        downloadJob?.cancel()
                        downloading = false
                        progress = null
                    } else {
                        updater.consume()
                    }
                }
            ) { Text(if (downloading) "取消下载" else "取消") }
        }
    )
}

/**
 * 更新日志轻量 Markdown 渲染：`##` 标题、`-` 列表、`**粗体**`，
 * 空行分段。覆盖 release notes 的常用语法，避免符号裸奔。
 */
@Composable
private fun ReleaseNotesText(notes: String) {
    notes.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("### ") -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            line.startsWith("## ") -> {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                Text(
                    text = buildRichText("• " + line.drop(2)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                )
            }
            line.isBlank() -> Spacer(modifier = Modifier.height(6.dp))
            else -> Text(
                text = buildRichText(line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp)
            )
        }
    }
}

/** `**粗体**` 片段渲染为粗体 span。 */
private fun buildRichText(line: String): AnnotatedString = buildAnnotatedString {
    var rest = line
    while (true) {
        val start = rest.indexOf("**")
        if (start < 0) {
            append(rest)
            break
        }
        val end = rest.indexOf("**", start + 2)
        if (end < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(rest.substring(start + 2, end))
        }
        rest = rest.substring(end + 2)
    }
}
