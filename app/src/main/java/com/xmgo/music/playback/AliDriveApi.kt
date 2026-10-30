package com.xmgo.music.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject

/**
 * 阿里云盘轻量客户端（P0：登录验证；直链/扫描 P2 接入）。
 * refresh_token → access_token（社区通用接口），token 由 CloudSourceManager 管。
 */
object AliDriveApi {
    private const val TAG = "AliDrive"
    private const val TOKEN_URL = "https://auth.alipan.com/v2/account/token"

    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val nickname: String?,
        val userId: String?
    )

    /** refresh_token 换 access_token（登录/续期同一入口）。 */
    suspend fun refreshAuth(refreshToken: String): Result<AuthResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("refresh_token", refreshToken)
                    .put("grant_type", "refresh_token")
                    .toString()
                val req = okhttp3.Request.Builder()
                    .url(TOKEN_URL)
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                    .build()
                okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                    val json = JSONObject(text)
                    AuthResult(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.getString("refresh_token"),
                        nickname = json.optString("user_name").takeIf { it.isNotEmpty() && it != "null" },
                        userId = json.optString("user_id").takeIf { it.isNotEmpty() && it != "null" }
                    )
                }
            }.onFailure { Log.w(TAG, "refreshAuth failed: $it") }
        }
}
