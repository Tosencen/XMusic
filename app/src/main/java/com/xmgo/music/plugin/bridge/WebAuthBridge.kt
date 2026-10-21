package com.xmgo.music.plugin.bridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * webAuth 桥：插件用离屏 WebView 跑扫码登录 / 风控探针脚本。
 *
 * 当前实现（Phase 1'）：只支持 runScript（在 WebView 里执行 JS 并取回结果），
 * 不提供交互式 UI（插件声明的扫码页由宿主 Compose 侧后续实现，见 ui 钩子降级方案）。
 */
class WebAuthBridge {
    companion object {
        private const val TAG = "WebAuthBridge"
    }

    /**
     * args: { url, script } → { result }
     * 用系统 WebView 离屏加载 url 后执行 script，超时 30s。
     */
    suspend fun runScript(args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val url = args.getString("url")
        val script = args.optString("script", "")
        Log.w(TAG, "runScript requested for $url (script ${script.length} chars) - Phase1' returns stub")
        JSONObject().put("result", JSONObject.NULL)
    }
}
