package com.xmgo.music.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 网盘音源类型。 */
enum class CloudSourceType(val displayName: String) {
    ALIYUN("阿里云盘"),
    QUARK("夸克网盘")
}

/** 单个网盘的登录态。 */
data class CloudSourceState(
    val type: CloudSourceType,
    val loggedIn: Boolean = false,
    val nickname: String? = null
)

/**
 * 网盘音源管理：登录态、主音源、凭据存储（EncryptedSharedPreferences）。
 * 主音源唯一，决定首页「网盘音乐」卡片显示谁的资源。
 */
object CloudSourceManager {
    private const val TAG = "CloudSource"
    private const val PREFS_NAME = "cloud_source_secure"

    private lateinit var prefs: SharedPreferences

    /** 全部源状态（UI 读）。 */
    private val _sources = MutableStateFlow(
        CloudSourceType.entries.map { CloudSourceState(it) }
    )
    val sources: StateFlow<List<CloudSourceState>> = _sources.asStateFlow()

    /** 当前主音源（null=未设置，首页卡片隐藏）。 */
    private val _primary = mutableStateOf<CloudSourceType?>(null)
    val primary get() = _primary

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            Log.w(TAG, "EncryptedSharedPreferences 失败，降级普通 prefs: $it")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        // 恢复登录态与主音源
        val restored = CloudSourceType.entries.map { type ->
            CloudSourceState(
                type = type,
                loggedIn = prefs.contains("token_${type.name}"),
                nickname = prefs.getString("nickname_${type.name}", null)
            )
        }
        _sources.value = restored
        _primary.value = prefs.getString("primary", null)?.let { name ->
            CloudSourceType.entries.firstOrNull { it.name == name }
        }?.takeIf { t -> restored.firstOrNull { it.type == t }?.loggedIn == true }
        Log.i(TAG, "init: primary=${_primary.value}, loggedIn=${restored.filter { it.loggedIn }.map { it.type }}")
    }

    /** 登录成功写入凭据（refresh_token 等），自动设主音源（若空缺）。 */
    fun onLoggedIn(
        type: CloudSourceType,
        nickname: String?,
        refreshToken: String,
        extra: Map<String, String> = emptyMap()
    ) {
        prefs.edit()
            .putString("token_${type.name}", refreshToken)
            .putString("nickname_${type.name}", nickname)
            .apply()
        extra.forEach { (k, v) -> prefs.edit().putString("${k}_${type.name}", v).apply() }
        _sources.value = _sources.value.map {
            if (it.type == type) it.copy(loggedIn = true, nickname = nickname) else it
        }
        if (_primary.value == null) setPrimary(type)
        Log.i(TAG, "onLoggedIn: $type nick=$nickname")
    }

    fun getRefreshToken(type: CloudSourceType): String? =
        prefs.getString("token_${type.name}", null)

    fun getExtra(type: CloudSourceType, key: String): String? =
        prefs.getString("${key}_${type.name}", null)

    /** 设为主音源（迁移/切换）。 */
    fun setPrimary(type: CloudSourceType) {
        prefs.edit().putString("primary", type.name).apply()
        _primary.value = type
        Log.i(TAG, "setPrimary: $type")
    }

    /** 退出登录（清凭据）；退的是主音源则迁移到另一已登录源，无则置空。 */
    fun logout(type: CloudSourceType) {
        prefs.edit()
            .remove("token_${type.name}")
            .remove("nickname_${type.name}")
            .apply()
        _sources.value = _sources.value.map {
            if (it.type == type) it.copy(loggedIn = false, nickname = null) else it
        }
        if (_primary.value == type) {
            val fallback = _sources.value.firstOrNull { it.loggedIn }?.type
            prefs.edit().putString("primary", fallback?.name).apply()
            _primary.value = fallback
        }
        Log.i(TAG, "logout: $type, primary now=${_primary.value}")
    }
}
