package com.xmgo.music.presentation.screens.home

import com.xmgo.music.plugin.model.AccountInfo
import com.xmgo.music.plugin.model.HomeContent
import kotlinx.coroutines.flow.MutableStateFlow

/** 首页内容缓存快照。 */
data class HomeCache(
    val primaryId: String? = null,
    val content: HomeContent? = null,
    val account: AccountInfo? = null,
    val tick: Int = 0
)

/**
 * 首页内容缓存（进程内单例）：
 * NavDisplay 离开首页时会销毁该页 composition，无缓存则每次返回都重新 home()。
 * 这里持有最近一次结果，返回时命中缓存直接展示，不再转圈刷新。
 */
class HomeContentStore {
    val cache = MutableStateFlow(HomeCache())
}
