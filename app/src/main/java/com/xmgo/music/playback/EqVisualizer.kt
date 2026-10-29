package com.xmgo.music.playback

import android.media.audiofx.Visualizer
import android.util.Log
import androidx.compose.runtime.mutableStateOf

/**
 * EQ 真频谱数据源：绑定播放器 audioSessionId，FFT 捕获 → 32 频段归一化幅度。
 * 数据驱动（约 15Hz 离散更新）——有数据才重绘，无连续动画时钟，天然流畅；
 * 跟随真实音乐律动（低频段=鼓点，高频段=镲片）。
 * 不可用（无权限/模拟器/ROM 阉割）时 [available] 为 false，调用方降级伪动画。
 */
object EqVisualizer {
    private const val TAG = "EqVisualizer"
    private const val BARS = 32

    /** 32 频段幅度（0..1），Canvas 绘制阶段读取（只重绘不重组）。 */
    val bands = mutableStateOf(FloatArray(BARS))

    /** 真频谱是否可用（绑定失败/无权限=false，调用方降级）。 */
    val available = mutableStateOf(false)

    private var visualizer: Visualizer? = null
    private var boundSession = -1

    /** 绑定/换绑播放器音频会话（重复同 session 幂等）。 */
    fun attach(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == boundSession) return
        release()
        runCatching {
            val v = Visualizer(audioSessionId)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1] // 最大 FFT 分辨率
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) {
                        val data = fft ?: return
                        // FFT 字节流：[re0,im0,re1,im1,...]，跳过直流（第 0 对）
                        // 32 频段对数分桶（低频分辨率高，贴近人耳感知）
                        val out = FloatArray(BARS)
                        val pairs = data.size / 2 - 1
                        if (pairs <= 0) return
                        for (bar in 0 until BARS) {
                            val start = pairs * bar * bar / (BARS * BARS)          // 对数起点
                            val end = pairs * (bar + 1) * (bar + 1) / (BARS * BARS) // 对数终点
                            var sum = 0f
                            var count = 0
                            for (i in start..end.coerceAtLeast(start)) {
                                val re = data[(i + 1) * 2].toInt()
                                val im = data[(i + 1) * 2 + 1].toInt()
                                val mag = kotlin.math.sqrt((re * re + im * im).toDouble()).toFloat()
                                sum += mag
                                count++
                            }
                            // 幅度归一化（FFT 字节幅度约 0..128*sqrt(2)，压到 0..1）
                            val amp = if (count > 0) (sum / count) / 90f else 0f
                            out[bar] = amp.coerceIn(0f, 1f)
                        }
                        // 轻度平滑（防突跳刺眼）：新值 60% + 旧值 40%
                        val old = bands.value
                        for (i in 0 until BARS) {
                            out[i] = out[i] * 0.6f + old.getOrElse(i) { 0f } * 0.4f
                        }
                        bands.value = out
                    }
                },
                Visualizer.getMaxCaptureRate() / 2, // ≈15Hz 离散更新（不卡）
                false,
                true
            )
            v.enabled = true
            visualizer = v
            boundSession = audioSessionId
            available.value = true
            Log.i(TAG, "attached session=$audioSessionId capture=${v.captureSize}")
        }.onFailure {
            Log.w(TAG, "attach failed: $it")
            available.value = false
        }
    }

    fun release() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        boundSession = -1
        available.value = false
    }
}
