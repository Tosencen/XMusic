package com.xmgo.music.plugin.bridge

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * credentials 桥：插件的 Cookie / token 持久化。
 * 存 EncryptedSharedPreferences，按 pluginId 分命名空间，插件间不可互见。
 */
class CredentialsBridge(context: Context) {
    companion object {
        private const val PREFIX = "xm_plugin_creds_"
    }

    private val appContext = context.applicationContext

    private fun prefsFor(pluginId: String): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFIX + pluginId,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** credentials.get → { value } 或 { value: null } */
    suspend fun get(pluginId: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val key = args.getString("key")
        val value = prefsFor(pluginId).getString(key, null)
        JSONObject().put("value", value ?: JSONObject.NULL)
    }

    /** credentials.put { key, value } → {} */
    suspend fun put(pluginId: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        prefsFor(pluginId).edit()
            .putString(args.getString("key"), args.getString("value"))
            .apply()
        JSONObject()
    }

    /** credentials.delete { key } → {} */
    suspend fun delete(pluginId: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        prefsFor(pluginId).edit().remove(args.getString("key")).apply()
        JSONObject()
    }
}
