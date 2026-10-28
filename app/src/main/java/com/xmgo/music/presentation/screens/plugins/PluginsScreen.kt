@file:OptIn(ExperimentalLayoutApi::class)

package com.xmgo.music.presentation.screens.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xmgo.music.R
import com.xmgo.music.data.datastore.rememberPrimarySource
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.catalog.CatalogRelease
import com.xmgo.music.plugin.model.AccountInfo
import com.xmgo.music.plugin.model.InstalledPlugin
import com.xmgo.music.presentation.login.LoginDialog
import com.xmgo.music.presentation.screens.settings.components.SettingsIconBadge
import com.xmgo.music.presentation.screens.settings.components.XmsSettingsItem
import kotlinx.coroutines.launch
import org.koin.compose.getKoin

/**
 * 插件与登录页（XMSLEEP 设置条目卡样式）：
 * 账号模块（登录入口）+ 插件模块（已安装 / 在线目录 / 操作卡）。
 * 已安装 = 独立小卡拼接行（图标徽章 + 名称 + 版本·主音源状态·可更新），
 * 点击行进详情弹窗（更新/主音源开关/登录/移除）。
 *
 * 主音源语义：优先识别并展示该音源内容（首页/搜索默认走它），不是启用/停用插件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onNavigateUp: () -> Unit) {
    val pluginManager: PluginManager = getKoin().get()
    val scope = rememberCoroutineScope()

    // 响应式：安装/卸载/登录由 PluginManager.pluginsFlow 统一推送，不再快照+手动刷新
    val installed by pluginManager.pluginsFlow.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }
    var loginTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var settingsTarget by remember { mutableStateOf<InstalledPlugin?>(null) }
    var catalog by remember { mutableStateOf<List<CatalogRelease>?>(null) }
    var catalogLoading by remember { mutableStateOf(false) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var showDisclaimer by remember { mutableStateOf(false) }

    var primarySource by rememberPrimarySource()

    // 账号（个人登录中心）：主音源优先，其次任意支持登录的音源
    val loginPlugin = remember(primarySource, installed) {
        installed.find {
            it.id == primarySource && pluginManager.hasCapability(it.id, "login")
        } ?: installed.firstOrNull { pluginManager.hasCapability(it.id, "login") }
    }
    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    LaunchedEffect(loginPlugin?.id) {
        account = loginPlugin?.let {
            runCatching { pluginManager.accountInfo(it.id) }.getOrNull()
        }
    }

    fun loadCatalog() {
        catalogLoading = true
        pluginManager.loadCatalog { releases, err ->
            catalog = releases
            if (err != null && releases.isEmpty()) error = "插件目录加载失败：$err"
            catalogLoading = false
        }
    }

    LaunchedEffect(Unit) { loadCatalog() }

    // 目录版本 ≠ 本地版本 → 可更新（目录加载失败时无此数据，不影响已装列表）
    val outdatedMap = remember(catalog, installed) {
        (catalog ?: emptyList())
            .filter { rel ->
                rel.version != "unknown" &&
                    installed.any { it.id == rel.source.id && it.manifest.version != rel.version }
            }
            .associateBy { it.source.id }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        error = null
        busy = true
        pluginManager.installFromUri(uri) { result ->
            result.exceptionOrNull()?.let { error = it.message ?: it.toString() }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件与登录") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        val catalogList = catalog ?: emptyList()
        val available = catalogList.filter { rel -> installed.none { it.id == rel.source.id } }

        // 横向不留 contentPadding：XmsSettingsItem 自带 16dp 内缩（规范边距），
        // 普通 Card 自行加 horizontal 16dp，避免双重缩进导致模块宽度不一致
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // 底部留出导航栏 + 额外间距，避免滑到底后末模块贴边
            contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- 账号 ----------
            item {
                SectionTitle(stringResource(R.string.account))
            }
            item {
                XmsSettingsItem(
                    icon = Icons.Rounded.Person,
                    topDp = 24.dp,
                    bottomDp = 24.dp,
                    title = stringResource(R.string.account_login),
                    description = when {
                        loginPlugin == null -> stringResource(R.string.account_no_plugin)
                        account?.loggedIn == true ->
                            "已登录：${account?.displayName}（${loginPlugin?.name}）"
                        else ->
                            "${loginPlugin?.name} · ${stringResource(R.string.account_not_logged_in)}"
                    },
                    onClick = { if (loginPlugin != null) showLogin = true }
                )
            }

            // ---------- 已安装 ----------
            item {
                SectionTitle("已安装")
            }
            if (installed.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SettingsIconBadge(Icons.Rounded.Extension)
                                Text(
                                    text = "还没有安装插件",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 14.dp)
                                )
                                Text(
                                    text = "从下方目录在线安装，或导入本地 .qplug 文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    // XMSLEEP 风格：独立小卡拼接（首 24/6、中 6/6、末 6/24），卡间距 4dp
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        installed.forEachIndexed { index, plugin ->
                            InstalledRow(
                                plugin = plugin,
                                isPrimary = plugin.id == primarySource,
                                updateVersion = outdatedMap[plugin.id]?.version,
                                topDp = if (installed.size == 1 || index == 0) 24.dp else 6.dp,
                                bottomDp = if (installed.size == 1 || index == installed.lastIndex) 24.dp else 6.dp,
                                onClick = { settingsTarget = plugin }
                            )
                        }
                    }
                }
            }

            // ---------- 可用（在线目录） ----------
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    SectionTitle("可用")
                    if (catalogLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            if (catalogList.isNotEmpty() && available.isEmpty()) {
                item {
                    Text(
                        text = "目录中的插件都已安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp)
                    )
                }
            }

            if (available.isNotEmpty()) {
                item {
                    // XMSLEEP 风格：独立小卡拼接，卡间距 4dp
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        available.forEachIndexed { index, release ->
                            CatalogCard(
                                release = release,
                                installing = installingId == release.source.id,
                                topDp = if (available.size == 1 || index == 0) 24.dp else 6.dp,
                                bottomDp = if (available.size == 1 || index == available.lastIndex) 24.dp else 6.dp,
                                onInstall = {
                                    installingId = release.source.id
                                    error = null
                                    pluginManager.installFromRelease(release) { result ->
                                        result.exceptionOrNull()?.let { error = it.message ?: it.toString() }
                                        installingId = null
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // ---------- 操作卡 ----------
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        TextButton(onClick = { loadCatalog() }, enabled = !catalogLoading) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .size(18.dp)
                            )
                            Text("刷新目录")
                        }
                        Box(modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { picker.launch(arrayOf("*/*")) },
                            enabled = !busy
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
                            Text(if (busy) "安装中…" else "导入 .qplug")
                        }
                    }
                }
            }

            error?.let { message ->
                item {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }

            // 底部：免责声明入口（与关于页同一弹窗组件）
            item {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    FilledTonalButton(onClick = { showDisclaimer = true }) {
                        Text("免责声明")
                    }
                }
            }
        }
    }

    if (showDisclaimer) {
        com.xmgo.music.presentation.components.DisclaimerDialog(
            onDismiss = { showDisclaimer = false }
        )
    }

    // ---------- 插件详情（更新 / 主音源 / 登录 / 移除） ----------
    settingsTarget?.let { plugin ->
        val isPrimary = plugin.id == primarySource
        val canLogin = pluginManager.hasCapability(plugin.id, "login")
        val updateRel = outdatedMap[plugin.id]
        AlertDialog(
            onDismissRequest = { settingsTarget = null },
            title = { Text(plugin.name) },
            text = {
                Column {
                    Text(
                        text = "v${plugin.manifest.version} · ${plugin.id}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    if (updateRel != null) {
                        OutlinedButton(
                            onClick = {
                                error = null
                                installingId = updateRel.source.id
                                pluginManager.installFromRelease(updateRel) { result ->
                                    result.exceptionOrNull()?.let { error = it.message ?: it.toString() }
                                    installingId = null
                                    // 刷新详情里的版本快照
                                    settingsTarget = pluginManager.plugins.find { it.id == updateRel.source.id }
                                }
                            },
                            enabled = installingId == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (installingId == updateRel.source.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Text("更新中…")
                            } else {
                                Text("更新到 v${updateRel.version}")
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("设为主音源", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "优先识别并展示该音源的内容",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isPrimary,
                            onCheckedChange = { on ->
                                primarySource = if (on) plugin.id else ""
                            }
                        )
                    }
                    if (canLogin) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                        OutlinedButton(
                            onClick = {
                                settingsTarget = null
                                loginTarget = plugin
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("登录") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { settingsTarget = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        settingsTarget = null
                        pendingUninstall = plugin
                    }
                ) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    pendingUninstall?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("移除插件") },
            text = { Text("确定移除「${plugin.name}」(${plugin.id})？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = plugin.id
                        pendingUninstall = null
                        if (primarySource == id) primarySource = ""
                        pluginManager.uninstallAsync(id) { result ->
                            result.exceptionOrNull()?.let { error = it.message ?: it.toString() }
                        }
                    }
                ) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) { Text("取消") }
            }
        )
    }

    loginTarget?.let { plugin ->
        if (pluginManager.hasCapability(plugin.id, "login")) {
            LoginDialog(
                pluginId = plugin.id,
                pluginName = plugin.name,
                onDismiss = { loginTarget = null },
                onSessionChanged = {
                    scope.launch {
                        account = runCatching { pluginManager.accountInfo(plugin.id) }.getOrNull()
                    }
                },
                pluginManager = pluginManager
            )
        } else {
            loginTarget = null
        }
    }

    if (showLogin && loginPlugin != null) {
        LoginDialog(
            pluginId = loginPlugin.id,
            pluginName = loginPlugin.name,
            onDismiss = { showLogin = false },
            onSessionChanged = {
                scope.launch {
                    account = runCatching { pluginManager.accountInfo(loginPlugin.id) }.getOrNull()
                }
            },
            pluginManager = pluginManager
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLargeEmphasized,
        color = MaterialTheme.colorScheme.primary,
        // 与设置页 SettingsWithTitle 标题对齐（卡片左缘 16 + 8 = 24）
        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 2.dp)
    )
}

/**
 * 已安装插件行（XMSLEEP 条目卡样式）：
 * 图标徽章 + 名称 + 「版本 · 主音源状态」摘要（有新版时追加「可更新 vX」），整行可点进详情。
 */
@Composable
private fun InstalledRow(
    plugin: InstalledPlugin,
    isPrimary: Boolean,
    updateVersion: String?,
    topDp: Dp,
    bottomDp: Dp,
    onClick: () -> Unit
) {
    XmsSettingsItem(
        icon = Icons.Rounded.Extension,
        title = plugin.name,
        description = "v${plugin.manifest.version} · " +
            (if (isPrimary) "主音源" else "已启用") +
            (updateVersion?.let { " · 可更新 v$it" } ?: ""),
        topDp = topDp,
        bottomDp = bottomDp,
        onClick = onClick
    )
}

/** 目录条目（XMSLEEP 条目卡样式）：图标徽章 + 名称/描述 + 安装按钮。 */
@Composable
private fun CatalogCard(
    release: CatalogRelease,
    installing: Boolean,
    topDp: Dp,
    bottomDp: Dp,
    onInstall: () -> Unit
) {
    XmsSettingsItem(
        icon = Icons.Rounded.Download,
        title = release.source.name,
        description = "v${release.version} · ${release.source.description}",
        topDp = topDp,
        bottomDp = bottomDp,
        trailing = {
            FilledTonalButton(
                onClick = onInstall,
                enabled = !installing
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(14.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(if (installing) "安装中" else "安装")
            }
        }
    )
}
