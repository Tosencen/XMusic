package com.xmgo.music.data.datastore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun <T> rememberPreference(
    key: Preferences.Key<T>,
    defaultValue: T,
): MutableState<T> {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val upstream by remember(key) {
        context.dataStore.data
            .map { it[key] ?: defaultValue }
    }.collectAsStateWithLifecycle(defaultValue)

    // 本地乐观值：setter 立即更新 UI（不再等 DataStore 一整轮回流的迟滞）；
    // 上游回流仅在无本地待写值时同步，避免把用户刚拖到的值盖回旧值
    val local: MutableState<T> = remember(key, defaultValue) { mutableStateOf(upstream) }
    var pendingWrite by remember(key) { mutableStateOf<T?>(null) }
    var writeJob by remember(key) { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(upstream) {
        if (pendingWrite == null) local.value = upstream
    }

    // 包装对象按 key 稳定（旧实现随每次状态变化重建，调用方持有的实例语义混乱）
    return remember(key, defaultValue) {
        object : MutableState<T> {
            override var value: T
                get() = local.value
                set(value) {
                    local.value = value
                    pendingWrite = value
                    // 滑条防抖：连续拖动合并为一次落盘（停顿 150ms 后写最后值），
                    // 避免每 tick 一次全量 Preferences 重写造成写风暴
                    writeJob?.cancel()
                    writeJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(150)
                        val target = pendingWrite ?: return@launch
                        pendingWrite = null
                        // 落盘阶段不可取消：否则"取消防抖协程"可能中断 edit 中途，丢最后一次写入
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            context.dataStore.edit { it[key] = target }
                        }
                    }
                }

            override fun component1() = value
            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}
