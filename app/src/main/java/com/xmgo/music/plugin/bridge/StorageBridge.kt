package com.xmgo.music.plugin.bridge

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * storage 桥：插件通用键值持久化（无权限要求，按 pluginId 分命名空间）。
 * put 存 {"v": value} 整包；get 返回 value 的 JSON 文本（resolve 成任意 JSON 值）。
 */
class StorageBridge(context: Context) {
    private val appContext = context.applicationContext

    private fun prefsFor(pluginId: String): SharedPreferences =
        appContext.getSharedPreferences("xm_plugin_storage_$pluginId", Context.MODE_PRIVATE)

    /** storage.get → value 的 JSON 文本（缺省 "null"）。 */
    fun get(pluginId: String, args: JSONObject): String {
        val key = args.getString("key")
        val raw = prefsFor(pluginId).getString(key, null) ?: return "null"
        val value = JSONObject(raw).opt("v") ?: return "null"
        return when (value) {
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }

    /** storage.delete { key } → 无返回值。 */
    fun delete(pluginId: String, args: JSONObject) {
        val key = args.getString("key")
        prefsFor(pluginId).edit().remove(key).apply()
    }

    /** storage.put { key, value } → 无返回值。 */
    fun put(pluginId: String, args: JSONObject) {
        val key = args.getString("key")
        val value = if (args.has("value")) args.get("value") else JSONObject.NULL
        prefsFor(pluginId).edit()
            .putString(key, JSONObject().put("v", value).toString())
            .apply()
    }
}
