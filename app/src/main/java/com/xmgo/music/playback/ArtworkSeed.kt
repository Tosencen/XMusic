package com.xmgo.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 随播放封面取色（qplayer monet 那套）：封面字节 → 主导色 seed → Theme 动态配色。
 * 对齐 qplayer：独立 IO 协程（不阻塞播放）、3s 拉取超时、网易 CDN 缩略图 128px、
 * revision 防切歌后陈旧回写；无封面时不清空 seed（主题不闪回默认色）。
 */
object ArtworkSeed {
    private const val TAG = "ArtworkSeed"

    private val _seed = MutableStateFlow<Color?>(null)
    val seed: StateFlow<Color?> = _seed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var revision = 0L

    fun extract(context: Context, artworkUri: Uri?) {
        val uri = artworkUri ?: return
        val rev = ++revision
        val scheme = uri.scheme
        scope.launch {
            val target = if (scheme == "http" || scheme == "https") thumbUrlIfNetease(uri.toString()) else uri.toString()
            val bytes = when {
                scheme == "http" || scheme == "https" -> fetchBytes(context, target)
                scheme == "file" -> runCatching { File(uri.path ?: return@launch).readBytes() }.getOrNull()
                else -> runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                Log.d(TAG, "extract failed (no bytes): rev=$rev uri=$target")
                return@launch
            }
            if (rev != revision) return@launch
            val color = dominantColor(bytes)
            if (color == null) {
                Log.d(TAG, "extract failed (no color): rev=$rev bytes=${bytes.size}")
                return@launch
            }
            val hex = ((color.red * 255).toInt() shl 16) or ((color.green * 255).toInt() shl 8) or (color.blue * 255).toInt()
            Log.d(TAG, "extract ok: rev=$rev bytes=${bytes.size} seed=#%06X".format(hex))
            if (rev == revision) _seed.value = color
        }
    }

    /** 仅网易 legacy CDN 支持 param 缩放（对齐 qplayer thumbUrl，且仅网易源可用）。 */
    private fun thumbUrlIfNetease(url: String): String {
        val isNetease = url.contains("music.126.net") || url.contains("music.163.com")
        if (!isNetease || url.contains("param=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "param=128y128"
    }

    @Volatile private var client: okhttp3.OkHttpClient? = null

    /** 共享 OkHttp（HTTP/2 + 连接复用 + 10MB 磁盘缓存），重复封面不再联网。 */
    private fun clientOf(context: Context): okhttp3.OkHttpClient =
        client ?: synchronized(this) {
            client ?: okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .cache(okhttp3.Cache(File(context.applicationContext.cacheDir, "artwork_http"), 10L * 1024 * 1024))
                .build()
                .also { client = it }
        }

    private fun fetchBytes(context: Context, url: String): ByteArray? = runCatching {
        val req = okhttp3.Request.Builder().url(url).build()
        clientOf(context).newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body.bytes() else null
        }
    }.getOrNull()

    /**
     * AndroidColorExtractor 直译：32px 采样、24 hue bin 按 chroma*value 加权，
     * 跳过低饱和/过暗/过曝像素，取最重 bin 的加权平均；全灰回退整体平均。
     */
    private fun dominantColor(bytes: ByteArray): Color? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= 32 && bounds.outHeight / (sampleSize * 2) >= 32) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val small = if (src.width == 32 && src.height == 32) src
        else Bitmap.createScaledBitmap(src, 32, 32, true)
        try {
            val binWeight = DoubleArray(24)
            val binR = DoubleArray(24)
            val binG = DoubleArray(24)
            val binB = DoubleArray(24)
            var avgR = 0.0; var avgG = 0.0; var avgB = 0.0; var avgN = 0
            val pixels = IntArray(32 * 32)
            small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            val hsv = FloatArray(3)
            for (p in pixels) {
                val r = AndroidColor.red(p); val g = AndroidColor.green(p); val b = AndroidColor.blue(p)
                avgR += r; avgG += g; avgB += b; avgN++
                AndroidColor.colorToHSV(p, hsv)
                val sat = hsv[1]; val val0 = hsv[2]
                if (sat < 0.2f || val0 < 0.15f || val0 > 0.95f) continue
                val w = sat.toDouble() * val0
                val bin = (hsv[0] / 360f * 24).toInt() % 24
                binWeight[bin] += w
                binR[bin] += r * w; binG[bin] += g * w; binB[bin] += b * w
            }
            var best = -1
            for (i in 0 until 24) if (best < 0 || binWeight[i] > binWeight[best]) best = i
            if (best >= 0 && binWeight[best] > 0) {
                val w = binWeight[best]
                return colorOf((binR[best] / w).toInt(), (binG[best] / w).toInt(), (binB[best] / w).toInt())
            }
            if (avgN == 0) return null
            return colorOf((avgR / avgN).toInt(), (avgG / avgN).toInt(), (avgB / avgN).toInt())
        } finally {
            if (small !== src) small.recycle()
            src.recycle()
        }
    }

    private fun colorOf(r: Int, g: Int, b: Int): Color {
        val cr = r.coerceIn(0, 255); val cg = g.coerceIn(0, 255); val cb = b.coerceIn(0, 255)
        return Color(0xFF000000 or (cr.toLong() shl 16) or (cg.toLong() shl 8) or cb.toLong())
    }
}
