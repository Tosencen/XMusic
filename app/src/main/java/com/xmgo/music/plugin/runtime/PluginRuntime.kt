package com.xmgo.music.plugin.runtime

import android.util.Log
import com.xmgo.music.plugin.bridge.CredentialsBridge
import com.xmgo.music.plugin.bridge.CryptoBridge
import com.xmgo.music.plugin.bridge.HttpBridge
import com.xmgo.music.plugin.bridge.StorageBridge
import com.xmgo.music.plugin.bridge.WebAuthBridge
import com.xmgo.music.plugin.model.InstalledPlugin
import com.xmgo.music.plugin.model.PluginManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.BaseFunction
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Rhino 沙箱运行时：加载 .qplug 插件 JS，暴露 qplayer 桥对象。
 * 复刻 qplayer PluginRuntime 的核心机制：
 * - CommonJS 包装 (function(exports,module,require,qplayer){ ... })
 * - qplayer = { call, register, pluginId }
 * - Promise ↔ CompletableFuture 桥接（宿主侧 future / 插件侧 Promise）
 * - require 仅允许包内相对路径
 */
class PluginRuntime(
    private val appContext: android.content.Context,
    private val plugin: InstalledPlugin,
    private val httpBridge: HttpBridge,
    private val cryptoBridge: CryptoBridge,
    private val credentialsBridge: CredentialsBridge,
    private val webAuthBridge: WebAuthBridge,
    private val storageBridge: StorageBridge
) {
    companion object {
        private const val TAG = "PluginRuntime"

        /**
         * 同文案 toast 30s 内只弹一次。状态放在 companion：跨插件运行时共享
         * （qq/netease 的 unblock 换源文案完全相同，分实例节流会各弹一次）。
         * check-then-act 必须在 [toastLock] 内原子完成（resolveStream 并发 4 路）。
         */
        private val toastLock = Any()

        @Volatile private var lastToastMessage: String? = null
        @Volatile private var lastToastAtMs: Long = 0L

        /** 后台补队列静默令牌集合：非空时吞掉插件 toast（只后台批量解析，不打扰用户）。 */
        private val quietTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()

        /**
         * 进入静默（后台 fillQueueAround 起点），返回令牌；与 [endQuietToasts] 成对。
         * 令牌幂等：同一令牌 end 两次无副作用，允许"取消旧补队列时提前结束"与
         * 旧任务 finally 兜底结束并存（避免旧静默吞掉新点击目标曲的换源提示）。
         */
        fun beginQuietToasts(): Any {
            val token = Any()
            quietTokens.add(token)
            return token
        }

        fun endQuietToasts(token: Any) {
            quietTokens.remove(token)
        }

        /** @return 是否应弹出（静默中或 30s 内同文案重复 → false，且不更新时间戳）。 */
        private fun shouldShowToast(msg: String): Boolean = synchronized(toastLock) {
            if (quietTokens.isNotEmpty()) {
                android.util.Log.d(TAG, "toast suppressed (quiet): $msg")
                return@synchronized false
            }
            val now = System.currentTimeMillis()
            if (msg == lastToastMessage && now - lastToastAtMs < 30_000L) {
                android.util.Log.d(TAG, "toast suppressed (dup): $msg")
                return@synchronized false
            }
            lastToastMessage = msg
            lastToastAtMs = now
            android.util.Log.d(TAG, "toast shown: $msg")
            true
        }
    }

    /** 桥返回值：isJson=true 时按 JSON 解析成对象/标量，否则作为裸字符串 resolve。 */
    private data class BridgeValue(val isJson: Boolean, val value: String?)

    private val manifest: PluginManifest = plugin.manifest
    // 承载阻塞的 HTTP/crypto 桥调用与微任务续接，必须用 IO（Default 池会被阻塞任务占死）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contexts = org.mozilla.javascript.ContextFactory()
    private var scopeObject: Scriptable? = null
    private var exportsObject: Scriptable? = null

    /** 同文案 toast 节流与后台静默状态在 companion（跨插件共享），见 [shouldShowToast]。 */
    private val lock = Any()

    /**
     * 单线程 actor：所有 JS 执行与微任务 pump 串行跑在这一条线程上。
     * Rhino 的微任务队列非线程安全，多线程并发 enterContext/pump 会丢任务
     * （表现为 resolveQueue 并发时 handler 永不 settle）。对齐 qplayer 的 submit 模型。
     */
    private val actor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-${manifest.id}-actor").apply { isDaemon = true }
    }

    /**
     * 取消传播 token：外层 handler future 被 Kotlin 协程取消后（点新歌取消 staged 补队列），
     * 已入队的 actor 任务跳过执行、JS .then 链不再继续（不再发起后续 HTTP）、
     * 在途 OkHttp Call 立即 cancel，快速释放单线程 actor 与 OkHttp 槽位。
     */
    private class InvokeToken {
        @Volatile var cancelled: Boolean = false
        private val inFlightCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<okhttp3.Call>()

        fun registerCall(call: okhttp3.Call) {
            if (cancelled) { call.cancel(); return }
            inFlightCalls.add(call)
            if (cancelled) { inFlightCalls.remove(call); call.cancel() }
        }

        fun onSettled(call: okhttp3.Call) {
            inFlightCalls.remove(call)
        }

        fun cancelCalls() {
            inFlightCalls.forEach { it.cancel() }
            inFlightCalls.clear()
        }
    }

    /** 当前正在执行的 handler token（actor 线程内 ThreadLocal，供 qplayer.call 关联所属调用）。 */
    private val currentInvoke = ThreadLocal<InvokeToken?>()

    @Volatile
    private var loaded = false

    @Volatile
    private var disposed = false

    val id: String get() = manifest.id

    /**
     * 释放运行时（卸载/覆盖安装插件时调用）：取消协程作用域、关闭单线程 actor、
     * 清空 Rhino 全局对象引用。已释放后再 invokeHandler 直接失败，不再排队任务。
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        try {
            scope.cancel()
        } catch (_: Throwable) {
        }
        actor.shutdown()
        synchronized(lock) {
            scopeObject = null
            exportsObject = null
            loaded = false
        }
        Log.i(TAG, "runtime ${manifest.id} disposed")
    }

    /** 确保插件已加载（幂等）。失败抛异常。 */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            load()
            loaded = true
        }
    }

    private fun load() {
        val cx = contexts.enterContext()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            scopeObject = scope

            val qplayer = NativeObject()
            qplayer.setParentScope(scope)
            qplayer.setPrototype(ScriptableObject.getObjectPrototype(scope))
            qplayer.put("call", qplayer, hostCall())
            qplayer.put("register", qplayer, register())
            qplayer.put("pluginId", qplayer, manifest.id)
            ScriptableObject.putProperty(scope, "qplayer", qplayer)

            val module = NativeObject()
            val exports = NativeObject()
            module.put("exports", module, exports)
            val requireFn = require(manifest.entry)

            val entryFile = File(plugin.dir, manifest.entry)
            val source = entryFile.readText()
            val wrapped = "(function(exports,module,require,qplayer){\n$source\n;return module.exports;})"
            val script = cx.compileString(wrapped, manifest.id + "/" + manifest.entry, 1, null)
            val fn = script.exec(cx, scope) as Function
            val returned = fn.call(cx, scope, scope, arrayOf(exports, module, requireFn, qplayer))
            val result = if (Undefined.isUndefined(returned)) exports else returned
            exportsObject = result as? Scriptable
                ?: throw IllegalStateException("plugin ${manifest.id} entry did not export an object")
            Log.i(TAG, "plugin ${manifest.id} v${manifest.version} loaded")
        } finally {
            Context.exit()
        }
    }

    /** 调用插件 handler（如 searchSongs），返回其 Promise  resolve 的值（JSON 字符串）。 */
    fun invokeHandler(name: String, argsJson: String): CompletableFuture<String> {
        if (disposed) {
            return CompletableFuture.failedFuture(
                IllegalStateException("plugin ${manifest.id} runtime disposed")
            )
        }
        val future = CompletableFuture<String>()
        val token = InvokeToken()
        // 协程取消 future 时同步标记 token 并 cancel 在途 OkHttp，令在途回调与后续 actor 任务跳过
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                token.cancelled = true
                token.cancelCalls()
            }
        }
        actor.execute {
            // 协程已取消（点新歌 / 页面离开）：跳过 JS 执行
            if (future.isCancelled) return@execute
            currentInvoke.set(token)
            val cx = contexts.enterContext()
            try {
                ensureLoaded()
                val scope = scopeObject!!
                val handlersObj = ScriptableObject.getProperty(exportsObject, "handlers")
                if (handlersObj !is Scriptable) {
                    future.completeExceptionally(IllegalStateException("plugin ${manifest.id} has no handlers"))
                    return@execute
                }
                val handler = ScriptableObject.getProperty(handlersObj, name)
                if (handler !is Function) {
                    future.completeExceptionally(IllegalStateException("plugin ${manifest.id} lacks handler: $name"))
                    return@execute
                }
                val jsArgs = if (argsJson.isBlank()) Undefined.instance else parseJson(cx, scope, argsJson)
                val result = handler.call(cx, scope, handlersObj, arrayOf(jsArgs))
                settle(cx, scope, result, future, token)
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            } finally {
                currentInvoke.remove()
                Context.exit()
            }
        }
        return future
    }

    /** 把 JS 侧返回值（可能是 Promise）收敛成 CompletableFuture<String>。 */
    private fun settle(cx: Context, scope: Scriptable, value: Any?, future: CompletableFuture<String>, token: InvokeToken) {
        val then = if (value is Scriptable) ScriptableObject.getProperty(value, "then") else Undefined.instance
        if (then !is Function) {
            future.complete(toJsonString(value))
            return
        }
        val resolve = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, t: Scriptable, args: Array<out Any?>): Any {
                future.complete(toJsonString(args.firstOrNull()))
                return Undefined.instance
            }
        }
        val reject = object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, t: Scriptable, args: Array<out Any?>): Any {
                future.completeExceptionally(
                    RuntimeException("plugin handler rejected: " + args.firstOrNull())
                )
                return Undefined.instance
            }
        }
        then.call(cx, scope, value as Scriptable, arrayOf(resolve, reject))
        // handler 若第一步就是 Promise 微任务（如 eapi 链的 checkTokenFor().then），
        // 注册回调后必须手动 pump，否则链永不起步、60s 超时。
        cx.processMicrotasks()
    }

    private fun parseJson(cx: Context, scope: Scriptable, json: String): Any? {
        val jsonNative = ScriptableObject.getProperty(scope, "JSON") as? Scriptable
            ?: throw IllegalStateException("JSON unavailable in plugin scope")
        val parse = ScriptableObject.getProperty(jsonNative, "parse") as Function
        // JSON.parse("null") 返回 JS null（Java null），不能用非空返回值断言
        return parse.call(cx, scope, jsonNative, arrayOf(json))
    }

    private fun toJsonString(value: Any?): String = when (value) {
        null, is Undefined -> "null"
        is String -> org.json.JSONObject.quote(value)
        is Scriptable -> {
            val cx = contexts.enterContext()
            try {
                val jsonNative = ScriptableObject.getProperty(scopeObject!!, "JSON") as? Scriptable
                    ?: return "null"
                val stringify = ScriptableObject.getProperty(jsonNative, "stringify") as Function
                (stringify.call(cx, scopeObject, jsonNative, arrayOf(value)) as? String) ?: "null"
            } finally {
                Context.exit()
            }
        }
        is Number, is Boolean -> value.toString()
        else -> value.toString()
    }

    // ---------- qplayer.call 桥 ----------

    private fun hostCall(): BaseFunction {
        return object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, t: Scriptable, args: Array<out Any?>): Any {
                val method = args.getOrNull(0) as? String
                    ?: throw IllegalArgumentException("qplayer.call: method must be string")
                val jsArgs = args.getOrNull(1) as? Scriptable ?: cx.newObject(s, "Object") as Scriptable
                val argsMap = jsToMap(jsArgs)

                // Promise executor 模式：宿主 future 完成后回调 resolve/reject
                val executor = object : BaseFunction() {
                    override fun call(cx: Context, s: Scriptable, t: Scriptable, fns: Array<out Any?>): Any {
                        val resolveFn = fns.getOrNull(0) as? Function
                        val rejectFn = fns.getOrNull(1) as? Function
                        // 捕获当前 handler 的取消 token：qplayer.call 在 handler JS 执行期间同步调用，
                        // ThreadLocal 此刻有效；HTTP 完成回调时用它判断外层是否已取消
                        val originToken = currentInvoke.get()
                        val future = dispatchHost(method, argsMap, originToken)
                        future.whenComplete { value, error ->
                            // 投递到 actor 串行执行，避免多线程并发 pump 微任务。
                            actor.execute {
                                // 外层调用已取消：不再回调 resolve/reject，JS .then 链自然终止，
                                // 不再发起后续 HTTP（当前在途请求由 OkHttp 自然结束）
                                if (originToken?.cancelled == true) return@execute
                                val cbCx = contexts.enterContext()
                                try {
                                    if (error == null) {
                                        val jsValue = when {
                                            value == null -> Undefined.instance
                                            value.isJson -> parseJson(cbCx, s, value.value ?: "null")
                                            else -> value.value ?: Undefined.instance
                                        }
                                        resolveFn?.call(cbCx, s, s, arrayOf(jsValue))
                                    } else {
                                        rejectFn?.call(cbCx, s, s, arrayOf(error.message ?: error.toString()))
                                    }
                                    // Rhino 微任务只在 JS 调用返回时自动 flush；宿主线程 resolve 后
                                    // 必须手动 pump，否则 .then 链永不执行、handler Promise 永不 settle。
                                    cbCx.processMicrotasks()
                                } finally {
                                    Context.exit()
                                }
                            }
                        }
                        return Undefined.instance
                    }
                }
                return cx.newObject(s, "Promise", arrayOf(executor))
            }
        }
    }

    /** 把桥方法转成 CompletableFuture<BridgeValue>。[token] 用于 http.request 取消联动。 */
    private fun dispatchHost(
        method: String,
        args: Map<String, Any?>,
        token: InvokeToken? = null
    ): CompletableFuture<BridgeValue> {
        val future = CompletableFuture<BridgeValue>()
        scope.launch {
            try {
                val argsJson = JSONObject(args).toString()
                val result: BridgeValue = when {
                    method == "http.request" -> {
                        val call = httpBridge.buildCall(JSONObject(argsJson))
                        token?.registerCall(call)
                        try {
                            BridgeValue(isJson = true, value = httpBridge.executeCall(call))
                        } finally {
                            token?.onSettled(call)
                        }
                    }
                    method == "compression.gunzip" ->
                        BridgeValue(isJson = false, value = cryptoBridge.dispatch(method, JSONObject(argsJson)))
                    method.startsWith("crypto.") ->
                        BridgeValue(isJson = false, value = cryptoBridge.dispatch(method, JSONObject(argsJson)))
                    method == "notifications.toast" -> {
                        val msg = JSONObject(argsJson).optString("message")
                        // unblock 换源提示（"已为该歌曲自动换源"）每首必弹、纯噪音：
                        // 换源行为本身保留（音源解锁开着就是这个语义），只吞提示
                        if (!msg.contains("自动换源") && shouldShowToast(msg)) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        BridgeValue(isJson = false, value = null)
                    }
                    method == "clipboard.write" -> {
                        val text = JSONObject(argsJson).optString("text")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(null, text))
                        }
                        BridgeValue(isJson = false, value = null)
                    }
                    method.startsWith("credentials.") -> when (method) {
                        "credentials.get" -> {
                            val obj = credentialsBridge.get(manifest.id, JSONObject(argsJson))
                            val v = if (obj.isNull("value")) null else obj.getString("value")
                            BridgeValue(isJson = false, value = v)
                        }
                        "credentials.put" -> {
                            credentialsBridge.put(manifest.id, JSONObject(argsJson))
                            BridgeValue(isJson = false, value = null)
                        }
                        "credentials.delete" -> {
                            credentialsBridge.delete(manifest.id, JSONObject(argsJson))
                            BridgeValue(isJson = false, value = null)
                        }
                        else -> throw IllegalArgumentException("unknown method: $method")
                    }
                    method.startsWith("storage.") -> when (method) {
                        "storage.get" ->
                            BridgeValue(isJson = true, value = storageBridge.get(manifest.id, JSONObject(argsJson)))
                        "storage.put" -> {
                            storageBridge.put(manifest.id, JSONObject(argsJson))
                            BridgeValue(isJson = false, value = null)
                        }
                        "storage.delete" -> {
                            storageBridge.delete(manifest.id, JSONObject(argsJson))
                            BridgeValue(isJson = false, value = null)
                        }
                        else -> throw IllegalArgumentException("unknown method: $method")
                    }
                    method.startsWith("webAuth.") -> when (method) {
                        "webAuth.runScript" ->
                            BridgeValue(isJson = false, value = webAuthBridge.runScript(JSONObject(argsJson)).toString())
                        else -> throw IllegalArgumentException("unknown method: $method")
                    }
                    else -> throw IllegalArgumentException("unknown host method: $method")
                }
                future.complete(result)
            } catch (e: Throwable) {
                Log.w(TAG, "dispatchHost $method failed: ${e.message}")
                future.completeExceptionally(e)
            }
        }
        return future
    }

    private fun jsToMap(js: Scriptable): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (key in js.ids) {
            val k = key.toString()
            val v = ScriptableObject.getProperty(js, k)
            map[k] = jsValueToJava(v)
        }
        return map
    }

    private fun jsValueToJava(v: Any?): Any? = when {
        v == null || Undefined.isUndefined(v) -> null
        v is String || v is Number || v is Boolean -> v
        v is Scriptable -> jsToMap(v)
        else -> v.toString()
    }

    private fun register(): BaseFunction {
        return object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, t: Scriptable, args: Array<out Any?>): Any {
                return Undefined.instance
            }
        }
    }

    // ---------- require：包内相对路径 ----------

    private fun require(parentPath: String): Function {
        return object : BaseFunction() {
            override fun call(cx: Context, s: Scriptable, t: Scriptable, args: Array<out Any?>): Any {
                val request = args.getOrNull(0) as? String
                    ?: throw IllegalArgumentException("require expects a relative path")
                val resolved = resolveModule(request, parentPath)
                val file = File(plugin.dir, resolved)
                if (!file.canonicalPath.startsWith(plugin.dir.canonicalPath)) {
                    throw SecurityException("require escapes plugin dir: $request")
                }
                if (!file.exists()) throw IllegalArgumentException("module not found: $request (resolved=$resolved)")
                Log.i(TAG, "require $request -> $resolved")
                val wrapped = "(function(exports,module,require,qplayer){\n${file.readText()}\n;return module.exports;})"
                val script = cx.compileString(wrapped, manifest.id + "/" + resolved, 1, null)
                val fn = script.exec(cx, s) as Function
                val module = NativeObject()
                val exports = NativeObject()
                module.put("exports", module, exports)
                val qplayer = ScriptableObject.getProperty(s, "qplayer")
                // 子模块的 require 以自身路径为基准（多层目录时相对解析才正确）
                val returned = fn.call(cx, s, s, arrayOf(exports, module, require(resolved), qplayer))
                return if (Undefined.isUndefined(returned)) exports else returned
            }
        }
    }

    private fun resolveModule(request: String, parentPath: String): String {
        val base = if (parentPath.isEmpty()) "" else parentPath.substringBeforeLast('/')
        var path = if (request.startsWith("/") || request.startsWith("./") || request.startsWith("../")) {
            if (base.isEmpty()) request.removePrefix("./") else "$base/$request"
        } else {
            request
        }
        if (!path.endsWith(".js")) path += ".js"
        // 归一化 .. 与 .
        val parts = path.split('/').toMutableList()
        val out = mutableListOf<String>()
        for (p in parts) {
            when (p) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(p)
            }
        }
        return out.joinToString("/")
    }
}
