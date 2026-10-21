package com.xmgo.music.presentation.shared_components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xmgo.music.data.datastore.rememberPrimarySource
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.catalog.CatalogRelease
import org.koin.compose.getKoin

/**
 * 首启/无插件音源配置弹窗（对齐 qplayer SourceSetupDialog 交互）：
 * - 目录卡片多选勾选，底部「确定」/「跳过」，另有「安装所有插件」一键全装；
 * - 点「确定」/「安装所有插件」后弹窗保持打开并显示安装进度，装好后自动进入应用
 *   （若立即关闭，首页会在后台安装完成前闪现无插件的默认样式）；
 * - 「跳过」记为已处理，之后不再弹出（仍可用 设置 → 插件管理 再装）；
 * - 目录加载中/失败（可重试）；从文件导入 .qplug（装好直接进应用）；
 * - 不可点遮罩关闭（closeOnScrim=false）。
 *
 * 由宿主在未确认（PLUGIN_SETUP_ACK=false）且当前无已装插件时渲染（见 Navigation）；
 * [onDone] 在用户完成任一路径后调用（写入 ack 标记，之后不再弹出）。
 */
@Composable
fun PluginSetupDialog(
    onDone: () -> Unit
) {
    val pluginManager: PluginManager = getKoin().get()
    var primarySource by rememberPrimarySource()

    var catalog by remember { mutableStateOf<List<CatalogRelease>?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var installedVer by remember { mutableStateOf(pluginManager.plugins) }
    var busy by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var installingIds by remember { mutableStateOf(setOf<String>()) }

    fun loadCatalog() {
        catalogLoading = true
        catalogError = null
        pluginManager.loadCatalog { releases, err ->
            catalog = releases
            catalogLoading = false
            if (err != null && releases.isEmpty()) catalogError = err
        }
    }

    remember { loadCatalog(); true }

    /** 安装勾选/全部插件；全部完成后才 [onDone] 进应用，避免首页闪现无插件样式。 */
    fun startInstall(ids: Set<String>) {
        if (installingIds.isNotEmpty()) return // 防重复点
        val toInstall = catalog.orEmpty()
            .filter { it.source.id in ids }
            .filter { rel -> installedVer.none { it.id == rel.source.id } }
        if (toInstall.isEmpty()) {
            onDone()
            return
        }
        toInstall.forEach { rel ->
            installingIds = installingIds + rel.source.id
            pluginManager.installFromRelease(rel) { result ->
                installingIds = installingIds - rel.source.id
                installedVer = pluginManager.plugins
                result.onSuccess { plugin ->
                    if (primarySource.isBlank()) primarySource = plugin.id
                }
                // 最后一个安装完成（无论成败）再进应用
                if (installingIds.isEmpty()) onDone()
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        pluginManager.installFromUri(uri) { result ->
            busy = false
            result.fold(
                onSuccess = { plugin ->
                    installedVer = pluginManager.plugins
                    primarySource = plugin.id
                    onDone()
                },
                onFailure = { error ->
                    catalogError = error.message ?: error.toString()
                }
            )
        }
    }

    AlertDialog(
        onDismissRequest = { /* closeOnScrim=false：仅按钮可关 */ },
        icon = {
            Icon(
                imageVector = Icons.Rounded.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("配置音乐来源") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "核心不内置在线音源。勾选想要的插件后点「确定」开始安装，" +
                        "装好后自动进入应用；也可以直接点「安装所有插件」。" +
                        "本地音乐无需安装插件即可使用。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (catalogLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 14.dp)
                    ) {
                        val transition = rememberInfiniteTransition(label = "catalogSync")
                        val rotation by transition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(700, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "rotation"
                        )
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(rotation)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "正在获取插件列表…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                catalog?.forEach { release ->
                    val id = release.source.id
                    val installed = installedVer.any { it.id == id }
                    CatalogSetupCard(
                        release = release,
                        installed = installed,
                        selected = id in selectedIds,
                        installing = id in installingIds,
                        onToggle = {
                            if (installed) return@CatalogSetupCard
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        }
                    )
                }

                if (!catalogLoading && catalog.isNullOrEmpty()) {
                    Text(
                        text = catalogError?.let { "暂时获取不到插件列表：$it" }
                            ?: "暂时获取不到插件列表。你仍可导入本地插件包，或稍后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                val installable = catalog.orEmpty()
                    .count { rel -> installedVer.none { it.id == rel.source.id } }
                if (!catalogLoading && installable > 0) {
                    OutlinedButton(
                        onClick = { startInstall(catalog.orEmpty().map { it.source.id }.toSet()) },
                        enabled = installingIds.isEmpty() && !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp)
                        )
                        Text(if (installingIds.isEmpty()) "安装所有插件（$installable）" else "安装中…")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    if (!catalogLoading && catalog.isNullOrEmpty()) {
                        TextButton(
                            onClick = { loadCatalog() },
                            enabled = installingIds.isEmpty() && !busy
                        ) {
                            Text("重新获取")
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = installingIds.isEmpty() && !busy
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.FileOpen,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(18.dp)
                            )
                        }
                        Text(if (busy) "安装中…" else "从文件导入插件")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { startInstall(selectedIds) },
                enabled = installingIds.isEmpty() && !busy
            ) {
                Text(if (selectedIds.isEmpty()) "确定" else "确定（已选 ${selectedIds.size}）")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDone()
                }
            ) {
                Text("跳过")
            }
        }
    )
}

@Composable
private fun CatalogSetupCard(
    release: CatalogRelease,
    installed: Boolean,
    selected: Boolean,
    installing: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .let { if (!installed) it.clickable(onClick = onToggle) else it }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = release.source.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = release.version,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else if (installed) {
                    Text(
                        text = "已安装",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle() }
                    )
                }
            }
            Text(
                text = release.source.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
