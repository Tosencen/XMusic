package com.xmgo.music.presentation.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.xmgo.music.plugin.PluginManager
import com.xmgo.music.plugin.model.AccountInfo
import com.xmgo.music.plugin.model.LoginChallenge
import com.xmgo.music.plugin.model.LoginMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 插件登录对话框：扫码（qr，轮询）+ 粘贴 Cookie（credential）。
 * 已登录时显示账号信息与退出入口。web 方式暂不支持（无 WebView 桩）。
 */
@Composable
fun LoginDialog(
    pluginId: String,
    pluginName: String,
    onDismiss: () -> Unit,
    onSessionChanged: () -> Unit = {},
    pluginManager: PluginManager
) {
    val scope = rememberCoroutineScope()

    var methods by remember { mutableStateOf<List<LoginMethod>?>(null) }
    var account by remember { mutableStateOf<AccountInfo?>(null) }
    var selectedMethod by remember { mutableStateOf<LoginMethod?>(null) }
    var challenge by remember { mutableStateOf<LoginChallenge?>(null) }
    var credential by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pluginId) {
        methods = runCatching { pluginManager.loginMethods(pluginId) }.getOrElse {
            error = it.message ?: it.toString()
            emptyList()
        }
        account = runCatching { pluginManager.accountInfo(pluginId) }.getOrNull()
    }

    // 扫码轮询：1.5s 一次，success / expired 停止。
    LaunchedEffect(challenge?.id) {
        val id = challenge?.id ?: return@LaunchedEffect
        while (isActive) {
            delay(1500)
            val next = runCatching { pluginManager.pollLogin(pluginId, id) }.getOrNull() ?: break
            // poll 返回不带 qrContent，保留 begin 的值
            challenge = next.copy(
                qrContent = next.qrContent.ifBlank { challenge?.qrContent.orEmpty() }
            )
            when (next.status) {
                "success" -> {
                    account = runCatching { pluginManager.accountInfo(pluginId) }.getOrNull()
                    onSessionChanged()
                    break
                }
                "expired" -> break
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (account?.loggedIn == true) pluginName else "登录 · $pluginName") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    methods == null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp) }

                    account?.loggedIn == true -> AccountPanel(account!!, pluginName)

                    selectedMethod == null -> MethodList(methods!!) { method ->
                        selectedMethod = method
                        error = null
                        if (method.type == "qr") {
                            busy = true
                            scope.launch {
                                runCatching { pluginManager.beginLogin(pluginId, method.id) }
                                    .onSuccess { challenge = it }
                                    .onFailure { error = it.message ?: it.toString() }
                                busy = false
                            }
                        }
                    }

                    selectedMethod!!.type == "qr" -> QrPanel(
                        pluginName = pluginName,
                        busy = busy,
                        challenge = challenge,
                        error = error,
                        onRetry = {
                            challenge = null
                            error = null
                            busy = true
                            scope.launch {
                                runCatching { pluginManager.beginLogin(pluginId, selectedMethod!!.id) }
                                    .onSuccess { challenge = it }
                                    .onFailure { error = it.message ?: it.toString() }
                                busy = false
                            }
                        }
                    )

                    selectedMethod!!.type == "credential" -> CredentialPanel(
                        method = selectedMethod!!,
                        credential = credential,
                        onCredentialChange = { credential = it },
                        busy = busy,
                        error = error,
                        onSubmit = {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    pluginManager.submitLogin(pluginId, selectedMethod!!.id, credential.trim())
                                }.onSuccess { ch ->
                                    if (ch.status == "success") {
                                        account = runCatching { pluginManager.accountInfo(pluginId) }.getOrNull()
                                        onSessionChanged()
                                    } else {
                                        error = ch.message.ifBlank { "登录失败" }
                                    }
                                }.onFailure { error = it.message ?: it.toString() }
                                busy = false
                            }
                        }
                    )

                    else -> Text(
                        text = "该登录方式（${selectedMethod!!.type}）暂不支持，请返回选择扫码或 Cookie。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (selectedMethod != null && account?.loggedIn != true) {
                    TextButton(
                        onClick = {
                            selectedMethod = null
                            challenge = null
                            credential = ""
                            error = null
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    ) { Text("返回登录方式") }
                }
            }
        },
        confirmButton = {
            if (account?.loggedIn == true) {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (account?.loggedIn == true) {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { pluginManager.logout(pluginId) }
                            account = runCatching { pluginManager.accountInfo(pluginId) }.getOrNull()
                            onSessionChanged()
                        }
                    }
                ) { Text("退出登录") }
            }
        }
    )
}

@Composable
private fun AccountPanel(account: AccountInfo, pluginName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        if (account.avatarUrl.isNotBlank()) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Person, contentDescription = null) }
        }
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = account.displayName.ifBlank { "已登录" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = buildString {
                    append(pluginName)
                    if (account.level > 0) append(" · Lv.${account.level}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MethodList(methods: List<LoginMethod>, onSelect: (LoginMethod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        methods.forEach { method ->
            val unsupported = method.type == "web"
            FilledTonalButton(
                onClick = { if (!unsupported) onSelect(method) },
                enabled = !unsupported,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = when (method.type) {
                        "qr" -> Icons.Rounded.QrCode
                        else -> Icons.Rounded.Link
                    },
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(if (unsupported) "${method.label}（暂不支持）" else method.label)
            }
            if (method.instructions.isNotBlank()) {
                Text(
                    text = method.instructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QrPanel(
    pluginName: String,
    busy: Boolean,
    challenge: LoginChallenge?,
    error: String?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            busy && challenge == null -> Box(
                modifier = Modifier.padding(40.dp)
            ) { CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp) }

            error != null && challenge == null -> Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )

            challenge?.qrContent.isNullOrBlank() -> Text(
                text = "二维码加载失败",
                style = MaterialTheme.typography.bodyMedium
            )

            else -> {
                val expired = challenge!!.status == "expired"
                if (expired) {
                    Text(
                        text = "二维码已过期",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    FilledTonalButton(onClick = onRetry) { Text("刷新二维码") }
                } else {
                    QrCode(content = challenge!!.qrContent, size = 220.dp)
                    Text(
                        text = when (challenge.status) {
                            "scanned" -> "已扫码，请在手机上确认"
                            "success" -> "登录成功"
                            else -> "请使用${pluginName} App 扫码"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialPanel(
    method: LoginMethod,
    credential: String,
    onCredentialChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (method.instructions.isNotBlank()) {
            Text(
                text = method.instructions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedTextField(
            value = credential,
            onValueChange = onCredentialChange,
            label = { Text(method.credentialLabel) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            enabled = !busy
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        FilledTonalButton(
            onClick = onSubmit,
            enabled = !busy && credential.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = 8.dp),
                    strokeWidth = 2.dp
                )
            }
            Text(if (busy) "验证中…" else "登录")
        }
    }
}

/** zxing 编码二维码并用 Canvas 绘制。 */
@Composable
private fun QrCode(content: String, size: androidx.compose.ui.unit.Dp) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 240, 240)
        }.getOrNull()
    }
    if (matrix == null) {
        Text("二维码生成失败", color = MaterialTheme.colorScheme.error)
        return
    }
    // 底=primary、码=onPrimary：跟随主题且保持扫码所需的高对比
    val bg = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    Canvas(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
    ) {
        drawRect(color = bg)
        val cell = this.size.width / matrix.width
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = fg,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(cell, cell)
                    )
                }
            }
        }
    }
}
