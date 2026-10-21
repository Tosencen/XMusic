package com.xmgo.music.presentation.screens.online

import com.xmgo.music.plugin.model.OnlineCollection

/**
 * 在线详情页缓存（进程内单例）：
 * Navigation3 离开页面会销毁 composition，无缓存则每次返回都重新
 * playlistDetails/albumDetails 网络请求 + 转圈。这里按 key 缓存最近结果，
 * 返回时命中先展示，后台静默刷新。
 */
class OnlineDetailCache {
    private val map = LinkedHashMap<String, OnlineCollection>(16, 0.75f, true)

    fun get(key: String): OnlineCollection? = synchronized(map) { map[key] }

    fun put(key: String, value: OnlineCollection) {
        synchronized(map) {
            map[key] = value
            while (map.size > 32) {
                val eldest = map.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
    }

    companion object {
        fun keyOf(kind: String, sourceId: String, id: String) = "$sourceId|$kind|$id"
    }
}
