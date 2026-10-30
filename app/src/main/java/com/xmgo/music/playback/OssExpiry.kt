package com.xmgo.music.playback

import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 从阿里 OSS 播放直链里解析**真实**的过期时刻（epoch 毫秒），未知返回 0。
 *
 * 为什么不用假定值：直链的 `expire_sec` 是**请求时**提的，实际生效时长由服务端决定，
 * 且不同签名版本的 URL 参数语义不同。假定固定 4 小时会误判——要么提前换链白打接口，
 * 要么拿已过期的链去播，被 CDN 拒（403）后播放器反复重载、跳转进度归零。
 *
 * 两种签名形态（都见过）：
 * 1. **OSS V4**：`x-oss-signature-version=OSS4-HMAC-SHA256`，
 *    `x-oss-expires` 是**寿命秒数**（不是时间戳！），真实过期 = `x-oss-date`（签名时刻）+ 该秒数。
 *    把它当 epoch 读会算出 1970 年 → 误判为「早就过期」。
 * 2. **老式**：`x-oss-expires` 直接就是 epoch 秒。
 *
 * 两个易错点：
 * - URL 可能是整体 URI 编码过的，但**不能整体 decode 再切参数**——签名值里可能有转义的
 *   `%26`（即 `&`），整体解码会把它当参数分隔符切碎。只能按 `&`/`=` 切完再逐个 decode。
 * - 非法/不支持的输入一律返回 0（调用方按「未知」处理，宁可重新取链）。
 */
object OssExpiry {

    private val V4_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** 过期时刻（epoch ms）；0 = 解析不出，按未知处理。 */
    fun expiryTimeMs(rawUrl: String): Long {
        // 整体编码过的 URL 才整体解码一次（普通 URL 不要动，否则签名里的 %26 会被解成 &）
        val target = if (rawUrl.contains("://")) rawUrl else try {
            URLDecoder.decode(rawUrl, "UTF-8")
        } catch (t: Throwable) {
            return 0L
        }
        val query = target.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return 0L

        val params = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            params[urlDecode(k) ?: continue] = urlDecode(v) ?: continue
        }

        val rawExpires = params["x-oss-expires"] ?: return 0L
        if (!rawExpires.all { it in '0'..'9' } || rawExpires.isEmpty()) return 0L
        val seconds = rawExpires.toLongOrNull() ?: return 0L
        if (seconds <= 0) return 0L

        val date = params["x-oss-date"]
        val isV4 = params["x-oss-signature-version"] == "OSS4-HMAC-SHA256"

        if (date != null || isV4) {
            // V4：expires 是寿命，必须配签名时刻；寿命上限 7 天，超出当非法
            if (date == null || !isV4Date(date) || seconds > 604_800) return 0L
            val signedAt = parseV4Date(date) ?: return 0L
            // 回写校验，避免非法日期（如 2 月 30 日）被 lenient 解析
            if (formatV4Date(signedAt) != date) return 0L
            return signedAt + seconds * 1000L
        }

        // 老式：expires 就是 epoch 秒
        val epochMs = seconds * 1000L
        return if (epochMs > 0) epochMs else 0L
    }

    /** 距过期还有多久（ms）；未知返回 [Long.MAX_VALUE]（调用方按「永不过期」处理）。 */
    fun remainingMs(rawUrl: String, nowMs: Long = System.currentTimeMillis()): Long {
        val t = expiryTimeMs(rawUrl)
        return if (t <= 0) Long.MAX_VALUE else (t - nowMs).coerceAtLeast(0L)
    }

    private fun isV4Date(s: String): Boolean =
        s.length == 16 && s[8] == 'T' && s[15] == 'Z' &&
            s.substring(0, 8).all { it in '0'..'9' } &&
            s.substring(9, 15).all { it in '0'..'9' }

    private fun parseV4Date(s: String): Long? = try {
        LocalDateTime.parse(s, V4_DATE).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (t: Throwable) {
        null
    }

    private fun formatV4Date(ms: Long): String =
        V4_DATE.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC))

    private fun urlDecode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        null
    }
}
