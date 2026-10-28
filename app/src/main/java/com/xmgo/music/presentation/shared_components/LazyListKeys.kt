package com.xmgo.music.presentation.shared_components

/**
 * 为可能含重复项的列表生成 LazyColumn/LazyRow 的稳定且唯一 key。
 *
 * 相比常见的 `"${id}_$index" 写法：
 * - 不混入 index——列表头部插入/删除时，后续项 key 不变，可复用组合状态（避免整列重绑）；
 * - 重复项（如播放队列里同一首歌出现多次）第 n 次出现追加 `#n`，保证 key 唯一不崩溃。
 *
 * 请配合 `remember(list) { stableKeys(list) { ... } }` 预先计算，key lambda 内按索引取值，
 * 避免在 key 求值阶段做 O(n) 查找。
 */
fun <T> stableKeys(items: List<T>, toBase: (T) -> String): List<String> {
    if (items.isEmpty()) return emptyList()
    val seen = HashMap<String, Int>(items.size)
    return items.map { item ->
        val base = toBase(item)
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "$base#$count"
    }
}
