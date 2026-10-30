package com.xmgo.music.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.xmgo.music.R
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 默认封面资源 URI（android.resource，任何消费者——media3 通知、系统媒体卡、锁屏——都能取到）。
 * applicationId 固定无后缀，免上下文拼接。
 */
val DEFAULT_ARTWORK_URI: Uri = Uri.parse("android.resource://com.xmgo.music/drawable/default_cover")

/**
 * 通知封面兜底加载器：封面缺失/加载失败时返回 [R.drawable.default_cover]，通知永不空窗。
 *
 * 为什么需要（缺图的两种形态）：
 * 1. metadata.artworkUri 为 null（插件歌无 artworkUrl、持久化恢复无封面）——
 *    默认实现 [BitmapLoader.loadBitmapFromMetadata] 返回 null → 通知无 largeIcon；
 * 2. artworkUri 指向的图加载失败（本地歌 `content://media/.../albumart` 无专辑图、
 *    在线封面 404/防盗链/坏 URI）→ future 失败 → 通知同样无 largeIcon。
 *
 * 通知一旦开天窗，部分国产 ROM 会用系统自己的占位图（如手势涂鸦）顶上默认封面，
 * 且无法从应用侧控制；本类保证 [androidx.media3.session.MediaSession] 的
 * BitmapLoader（DefaultMediaNotificationProvider 显式取用）永远产出 bitmap。
 *
 * 只依赖 ListenableFuture 接口本身（media3 公开 API 自带），不引 guava 工具类。
 */
@UnstableApi
class FallbackBitmapLoader(context: Context) : BitmapLoader {

    private val appContext = context.applicationContext
    private val delegate = DataSourceBitmapLoader(appContext)

    /** 默认封面解码一次复用（线程安全的 lazy）。 */
    private val defaultBitmap: Bitmap by lazy {
        val drawable = ContextCompat.getDrawable(appContext, R.drawable.default_cover)
            ?: error("default_cover drawable not found")
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bmp))
        bmp
    }

    override fun supportsMimeType(mimeType: String): Boolean = delegate.supportsMimeType(mimeType)

    override fun decodeBitmap(bytes: ByteArray): ListenableFuture<Bitmap> =
        withFallback(delegate.decodeBitmap(bytes))

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        // 默认封面 URI 指向 drawable XML：RawResourceDataSource 打不开（仅支持 res/raw），
        // 直接返回解码好的 bitmap；系统侧（MediaDataManager）取不到时也会回落到通知 largeIcon
        if (uri == DEFAULT_ARTWORK_URI) {
            return ImmediateFuture<Bitmap>().apply { complete(defaultBitmap) }
        }
        return withFallback(delegate.loadBitmap(uri))
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
        // 完全无封面数据时默认实现返回 null（调用方按"无图"处理 → 通知开天窗），这里直接兜底
        val future = delegate.loadBitmapFromMetadata(metadata)
            ?: return ImmediateFuture<Bitmap>().apply { complete(defaultBitmap) }
        return withFallback(future)
    }

    /** 成功用结果；失败（404/无专辑图/坏 URI/解码失败/取消）用默认封面。 */
    private fun withFallback(future: ListenableFuture<Bitmap>): ListenableFuture<Bitmap> {
        val out = ImmediateFuture<Bitmap>()
        future.addListener({
            val result = runCatching { future.get() }.getOrElse {
                Log.w(TAG, "artwork load failed, fallback to default: ${it.message}")
                null
            }
            out.complete(result ?: defaultBitmap)
        }, DIRECT_EXECUTOR)
        return out
    }

    /**
     * 极简已完成/挂起 future：未 [complete] 前 addListener 登记监听，完成时在给定 executor 上派发
     * （语义对齐 guava 的 SettableFuture/immediateFuture，避免引入 guava 工具类依赖）。
     */
    private class ImmediateFuture<T> : ListenableFuture<T> {
        private val lock = Any()
        private var value: T? = null
        private var done = false
        private val pending = ArrayList<Pair<Runnable, Executor>>()

        fun complete(v: T) {
            val listeners: List<Pair<Runnable, Executor>>
            synchronized(lock) {
                value = v
                done = true
                listeners = pending.toList()
                pending.clear()
            }
            listeners.forEach { (r, e) -> e.execute(r) }
        }

        override fun addListener(runnable: Runnable, executor: Executor) {
            synchronized(lock) {
                if (!done) {
                    pending.add(runnable to executor)
                    return
                }
            }
            executor.execute(runnable)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = synchronized(lock) { done }
        override fun get(): T = synchronized(lock) { value ?: error("future not completed") }
        override fun get(timeout: Long, unit: TimeUnit): T = get()
    }

    private companion object {
        const val TAG = "FallbackBitmapLoader"
        val DIRECT_EXECUTOR: Executor = Executor { it.run() }
    }
}
