package com.xmgo.music.plugin.bridge

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * http.request 桥：把 JS 侧 { url, method, headers, body } 变成 OkHttp 调用。
 * 结果序列化为 JSON 字符串回传（插件侧再做 JSON.parse）。
 */
class HttpBridge(
    private val client: OkHttpClient,
    private val allowedDomains: Set<String> = emptySet()
) {

    /** 域名白名单为空时放行（自用模式）；非空时按 plugin.json networkDomains 校验。 */
    private fun domainAllowed(url: String): Boolean {
        if (allowedDomains.isEmpty()) return true
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return allowedDomains.any { pattern ->
            if (pattern.startsWith("*.")) {
                host == pattern.removePrefix("*.") || host.endsWith(pattern.removePrefix("*.").let { ".$it" })
            } else {
                host == pattern
            }
        }
    }

    /** 构建 OkHttp Call（不执行）：供调用方注册取消联动（staged 补队列取消时 call.cancel()）。 */
    fun buildCall(args: JSONObject): okhttp3.Call {
        val url = args.getString("url")
        val method = args.optString("method", "GET").uppercase()
        if (!domainAllowed(url)) throw SecurityException("domain not allowed: $url")

        val body = if (args.has("body") && !args.isNull("body")) args.getString("body") else null
        val headersObj = args.optJSONObject("headers")
        // Content-Type 必须以插件 headers 优先：OkHttp 的 RequestBody 自带 mediaType
        // 会覆盖已设置的同名 header（BridgeInterceptor），否则 weapi/eapi 的
        // x-www-form-urlencoded 会被默认的 application/json 顶掉 → 服务端 200 空响应。
        val contentType = headersObj?.optString("Content-Type")?.takeIf { it.isNotBlank() }
            ?: args.optString("contentType", "application/json").takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        val builder = Request.Builder().url(url)
        // 先放默认 UA，插件自带的 User-Agent 头随后覆盖
        builder.header("User-Agent", "QPlayer-Plugin/1")
        headersObj?.let { h ->
            h.keys().forEach { k -> builder.header(k, h.getString(k)) }
        }

        when (method) {
            "GET", "HEAD" -> builder.method(method, null)
            "POST", "PUT", "PATCH", "DELETE" ->
                builder.method(method, body?.toRequestBody(contentType.toMediaType()))
            else -> throw IllegalArgumentException("unsupported method: $method")
        }

        // 插件可传 timeoutMs（如 unblock 第三方源 8s/15s）；忽略会让 OkHttp 默认
        // 30s read timeout 拖死整条 resolveQueue。
        val timeoutMs = args.optLong("timeoutMs", 0L).takeIf { it > 0 }
        val callClient = if (timeoutMs != null) {
            client.newBuilder().callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        } else client
        return callClient.newCall(builder.build())
    }

    /** 执行已构建的 Call 并序列化响应（阻塞 IO，调用方自行切协程）。 */
    fun executeCall(call: okhttp3.Call): String = call.execute().use { resp ->
        val text = resp.body?.string() ?: ""
        val out = JSONObject()
        out.put("status", resp.code)
        out.put("body", text)
        val hdrs = JSONObject()
        resp.headers.forEach { (k, v) ->
            if (hdrs.has(k)) hdrs.put(k, hdrs.getString(k) + ", " + v) else hdrs.put(k, v)
        }
        out.put("headers", hdrs)
        // 插件靠 setCookies 吸收 Set-Cookie（登录 MUSIC_U 等），必须保持数组
        out.put("setCookies", JSONArray(resp.headers.values("Set-Cookie")))
        out.toString()
    }

    suspend fun request(args: JSONObject): String = withContext(Dispatchers.IO) {
        executeCall(buildCall(args))
    }

    suspend fun requestMap(args: Map<String, Any?>): String =
        request(JSONObject(args.mapValues { it.value ?: JSONObject.NULL }))
}

/**
 * crypto 桥：对齐 QPlayer ABI v1 的 crypto.* 语义。
 * - 输入字段带 *Encoding（utf8 | hex | base64，缺省 utf8）
 * - aes 用 transformation（如 AES/CBC/PKCS5Padding、AES/GCM/NoPadding）+ operation
 * - 所有方法 resolve **裸字符串**（插件直接当值用，非 JSON 包装）
 */
class CryptoBridge {
    fun dispatch(method: String, args: JSONObject): String = when (method) {
        "crypto.aes" -> aes(args)
        "crypto.digest" -> digest(args)
        "crypto.hmac" -> hmac(args)
        "crypto.modPow" -> modPow(args)
        "crypto.random" -> random(args)
        "crypto.x25519" -> x25519(args)
        "compression.gunzip" -> gunzip(args)
        else -> throw IllegalArgumentException("unknown crypto method: $method")
    }

    private fun decode(value: String, encoding: String): ByteArray = when (encoding) {
        "utf8", "utf-8" -> value.toByteArray(Charsets.UTF_8)
        "hex" -> hexToBytes(value)
        else -> Base64.getDecoder().decode(value)
    }

    private fun encode(bytes: ByteArray, encoding: String): String = when (encoding) {
        "utf8", "utf-8" -> String(bytes, Charsets.UTF_8)
        "hex" -> bytes.joinToString("") { "%02x".format(it) }
        else -> Base64.getEncoder().encodeToString(bytes)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "hex length must be even" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /** AES 加/解密。transformation + operation(encrypt|decrypt) + *Encoding 字段。 */
    private fun aes(args: JSONObject): String {
        val transformation = args.optString("transformation", "AES/ECB/PKCS5Padding")
        val operation = args.optString("operation", args.optString("op", "encrypt"))
        val key = decode(args.getString("key"), args.optString("keyEncoding", "utf8"))
        val data = decode(args.getString("data"), args.optString("dataEncoding", "utf8"))
        val cipher = javax.crypto.Cipher.getInstance(transformation)
        val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
        val mode =
            if (operation == "decrypt") javax.crypto.Cipher.DECRYPT_MODE
            else javax.crypto.Cipher.ENCRYPT_MODE
        val hasIv = args.has("iv") && !args.isNull("iv") && args.getString("iv").isNotEmpty()
        when {
            transformation.contains("GCM") -> {
                val iv = decode(args.getString("iv"), args.optString("ivEncoding", "utf8"))
                cipher.init(mode, keySpec, javax.crypto.spec.GCMParameterSpec(128, iv))
            }
            hasIv -> {
                val iv = decode(args.getString("iv"), args.optString("ivEncoding", "utf8"))
                cipher.init(mode, keySpec, javax.crypto.spec.IvParameterSpec(iv))
            }
            else -> cipher.init(mode, keySpec)
        }
        return encode(cipher.doFinal(data), args.optString("outputEncoding", "base64"))
    }

    /** 摘要 → hex/base64。dataEncoding 缺省 utf8（插件传明文）。 */
    private fun digest(args: JSONObject): String {
        val algorithm = args.optString("algorithm", "MD5")
        val md = MessageDigest.getInstance(
            when (algorithm.uppercase().replace("-", "")) {
                "MD5" -> "MD5"
                "SHA1" -> "SHA-1"
                "SHA256" -> "SHA-256"
                "SHA512" -> "SHA-512"
                else -> algorithm
            }
        )
        val data = decode(args.getString("data"), args.optString("dataEncoding", "utf8"))
        return encode(md.digest(data), args.optString("outputEncoding", "hex"))
    }

    /** HMAC。algorithm 如 HmacSHA256。 */
    private fun hmac(args: JSONObject): String {
        val algorithm = args.optString("algorithm", "HmacSHA256")
            .replace("Hmac", "Hmac")
            .let { if (it.startsWith("Hmac")) it else "Hmac$it" }
        val mac = javax.crypto.Mac.getInstance(algorithm)
        val key = decode(args.getString("key"), args.optString("keyEncoding", "utf8"))
        mac.init(javax.crypto.spec.SecretKeySpec(key, algorithm))
        val data = decode(args.getString("data"), args.optString("dataEncoding", "utf8"))
        return encode(mac.doFinal(data), args.optString("outputEncoding", "base64"))
    }

    /** 模幂（weapi encSecKey）。字段 baseHex/exponentHex/modulusHex（hex），width 位宽。 */
    private fun modPow(args: JSONObject): String {
        val base = if (args.has("baseHex")) {
            java.math.BigInteger(1, hexToBytes(args.getString("baseHex")))
        } else {
            val baseStr = args.getString("base")
            if (baseStr.matches(Regex("-?\\d+"))) java.math.BigInteger(baseStr)
            else java.math.BigInteger(1, Base64.getDecoder().decode(baseStr))
        }
        val expHex = args.optString("exponentHex", "")
        val exp = if (expHex.isNotEmpty()) java.math.BigInteger(1, hexToBytes(expHex))
        else java.math.BigInteger(args.getString("exp"))
        val modHex = args.optString("modulusHex", "")
        val mod = if (modHex.isNotEmpty()) java.math.BigInteger(1, hexToBytes(modHex))
        else java.math.BigInteger(args.getString("mod"))
        val width = args.optInt("width", 256)
        return base.modPow(exp, mod).toString(16).padStart(width, '0')
    }

    /** gzip 解压。dataEncoding/outputEncoding 同 crypto.* 语义。 */
    private fun gunzip(args: JSONObject): String {
        val data = decode(args.getString("data"), args.optString("dataEncoding", "utf8"))
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                require(out.size() + n <= 32 * 1024 * 1024) { "gunzip output too large" }
                out.write(buf, 0, n)
            }
        }
        return encode(out.toByteArray(), args.optString("outputEncoding", "utf8"))
    }

    /** 随机字节。outputEncoding: base64（缺省）| hex。 */
    private fun random(args: JSONObject): String {
        val length = args.optInt("length", 16)
        val bytes = ByteArray(length).also { java.security.SecureRandom().nextBytes(it) }
        return encode(bytes, args.optString("outputEncoding", "base64"))
    }

    /** X25519：无 point 时用 base point 生成公钥；有 point 时算共享密钥。 */
    private fun x25519(args: JSONObject): String {
        val scalar = decode(args.getString("scalar"), args.optString("scalarEncoding", "hex"))
        val point = if (args.has("point") && !args.isNull("point")) {
            decode(args.getString("point"), args.optString("pointEncoding", "utf8"))
        } else {
            X25519.BASE_POINT
        }
        val out = X25519.scalarMult(scalar, point)
        return encode(out, args.optString("outputEncoding", "hex"))
    }
}
