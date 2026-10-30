package com.xmgo.music.ffmpeg

/**
 * avformat 解封装的 JNI 封装（XMMusic 新增）。
 *
 * 打开的是**文件路径**而不是流：media3 的 ExtractorInput 只能前进、不能随机 seek，
 * 而 avformat 解封装时经常要回退，所以由 [FfmpegExtractor] 先把整条流落到临时文件。
 * 详见 `ffmpeg-ext/src/main/jni/asf_demux_jni.cc` 的文件头注释。
 *
 * 所有方法都是「句柄」式：[open] 返回非 0 句柄，用完必须 [close]。
 */
class FfmpegDemux private constructor(private var handle: Long) {

    fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    /** FFmpeg 的 codec 名（如 `wmav2`/`wmapro`/`ape`），用于精确选解码器。 */
    val codecName: String get() = if (handle == 0L) "" else nativeGetCodecName(handle).orEmpty()
    val sampleRate: Int get() = if (handle == 0L) 0 else nativeGetSampleRate(handle)
    val channelCount: Int get() = if (handle == 0L) 0 else nativeGetChannels(handle)

    /** 时长（微秒）；未知返回 -1。 */
    val durationUs: Long get() = if (handle == 0L) -1L else nativeGetDurationUs(handle)

    /** 解码器所需的 extradata（WMA/ALAC 等），无则 null。 */
    val extraData: ByteArray? get() = if (handle == 0L) null else nativeGetExtraData(handle)

    /** 块对齐：ASF/WMA 解码必需，缺失会让 avcodec_open2 报 Invalid argument。 */
    val blockAlign: Int get() = if (handle == 0L) 0 else nativeGetBlockAlign(handle)
    val bitsPerCodedSample: Int get() = if (handle == 0L) 0 else nativeGetBitsPerCodedSample(handle)

    /**
     * 读下一个音频包。返回压缩数据；读完或出错返回 null。
     * @param ptsUs out，1 个元素：展示时间戳（微秒），未知为 -1
     * @param isKeyFrame out，1 个元素
     * @param bytePos out，1 个元素：包在**原始流**里的字节偏移（给 media3 的 SeekMap 用，
     *   否则它无法判断「某段是否已加载」，会反复从头重载）
     */
    fun readPacket(ptsUs: LongArray, isKeyFrame: BooleanArray, bytePos: LongArray): ByteArray? {
        if (handle == 0L) return null
        return nativeReadPacket(handle, ptsUs, isKeyFrame, bytePos)
    }

    /** 定位到时间戳（微秒）附近的关键帧。 */
    fun seek(timeUs: Long): Boolean = handle != 0L && nativeSeek(handle, timeUs)

    companion object {
        init {
            // 与 media3 的 FfmpegLibrary 用同一个 .so（CMake 目标名 ffmpegJNI）
            System.loadLibrary("ffmpegJNI")
        }

        /** 打开文件做解封装；失败返回 null。 */
        @JvmStatic
        fun open(path: String): FfmpegDemux? {
            val h = nativeOpen(path)
            return if (h == 0L) null else FfmpegDemux(h)
        }

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeGetCodecName(handle: Long): String?
        @JvmStatic private external fun nativeGetSampleRate(handle: Long): Int
        @JvmStatic private external fun nativeGetChannels(handle: Long): Int
        @JvmStatic private external fun nativeGetDurationUs(handle: Long): Long
        @JvmStatic private external fun nativeGetExtraData(handle: Long): ByteArray?
        @JvmStatic private external fun nativeGetBlockAlign(handle: Long): Int
        @JvmStatic private external fun nativeGetBitsPerCodedSample(handle: Long): Int
        @JvmStatic private external fun nativeReadPacket(
            handle: Long, ptsUs: LongArray, isKeyFrame: BooleanArray, bytePos: LongArray
        ): ByteArray?
        @JvmStatic private external fun nativeSeek(handle: Long, timeUs: Long): Boolean
    }
}
