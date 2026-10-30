package com.xmgo.music.playback

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 阿里云盘扫码登录（生成式，itxve/aliyundriver-refresh-token 实证接口）：
 * generate.do 生成二维码会话 → ZXing 渲染 → App 扫码 → query.do 轮询 → bizExt 解出 refresh_token。
 */
object QrLogin {
    private const val TAG = "QrLogin"

    enum class Status { NEW, SCANED, CONFIRMED, CANCELLED, EXPIRED, UNKNOWN }

    data class QrSession(
        val t: String,   // 轮询参数（非时间戳）
        val ck: String,  // 轮询参数
        val content: String // 二维码内容（codeContent）
    )

    private val client by lazy { okhttp3.OkHttpClient.Builder().followRedirects(true).build() }

    private const val ALI_BASE = "https://passport.aliyundrive.com/newlogin/qrcode"
    private const val ALI_PARAMS =
        "appName=aliyun_drive&fromSite=52&appEntrance=web&isMobile=false&lang=zh"

    /** 生成二维码会话（响应嵌套：content.data.{codeContent,ck,t}）。 */
    suspend fun aliGenerate(): Result<QrSession> = withContext(Dispatchers.IO) {
        runCatching {
            val req = okhttp3.Request.Builder()
                .url("$ALI_BASE/generate.do?$ALI_PARAMS")
                .post(okhttp3.RequestBody.create(null, ""))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val root = JSONObject(text)
                val data = root.optJSONObject("content")?.optJSONObject("data") ?: root
                val content = data.optString("codeContent").takeIf { it.isNotEmpty() }
                    ?: error("无 codeContent: ${text.take(200)}")
                QrSession(
                    t = data.optString("t"),
                    ck = data.optString("ck").takeIf { it.isNotEmpty() } ?: error("无 ck"),
                    content = content
                )
            }
        }.onFailure { Log.w(TAG, "aliGenerate failed: $it") }
    }

    /** 轮询扫码状态（itxve state-query.ts 源码实证：POST form 全参数 + URL 带 _bx-v）。 */
    suspend fun aliQuery(session: QrSession): Result<Pair<Status, String?>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val form = okhttp3.FormBody.Builder()
                    .add("t", session.t)
                    .add("ck", session.ck)
                    .add("appName", "aliyun_drive")
                    .add("appEntrance", "web")
                    .add("isMobile", "false")
                    .add("lang", "zh_CN")
                    .add("returnUrl", "")
                    .add("fromSite", "52")
                    .add("bizParams", "")
                    .add("navlanguage", "zh-CN")
                    .add("navPlatform", "MacIntel")
                    .build()
                val req = okhttp3.Request.Builder()
                    .url("https://passport.aliyundrive.com/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52&_bx-v=2.0.31")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .post(form)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    Log.i(TAG, "aliQuery raw: ${text.take(500)}")
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val root = JSONObject(text)
                    val data = root.optJSONObject("content")?.optJSONObject("data") ?: root
                    val status = when (data.optString("qrCodeStatus").uppercase()) {
                        "NEW" -> Status.NEW
                        "SCANED", "SCANDED" -> Status.SCANED
                        "CONFIRMED" -> Status.CONFIRMED
                        "CANCELED", "CANCELLED" -> Status.CANCELLED
                        "EXPIRED" -> Status.EXPIRED
                        else -> Status.UNKNOWN
                    }
                    // CONFIRMED：bizExt=base64 → 解码 JSON 取 refreshToken
                    var token: String? = null
                    val bizExt = data.optString("bizExt").takeIf { it.isNotEmpty() }
                    if (bizExt != null) {
                        runCatching {
                            val decoded = android.util.Base64.decode(bizExt, android.util.Base64.DEFAULT)
                            val j = JSONObject(String(decoded))
                            token = j.optString("refreshToken").takeIf { it.isNotEmpty() }
                                ?: j.optString("refresh_token")
                        }
                    }
                    status to token
                }
            }.onFailure { Log.w(TAG, "aliQuery failed: $it") }
        }

    /** ZXing 生成二维码 Bitmap。 */
    fun qrBitmap(content: String, size: Int = 640): Bitmap? = runCatching {
        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val bitMatrix = writer.encode(
            content,
            com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        bmp
    }.getOrNull()
}
