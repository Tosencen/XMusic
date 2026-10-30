package com.xmgo.music.ffmpeg

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.File
import java.io.FileOutputStream

/**
 * 用 FFmpeg 的 libavformat 解封装 media3 缺的容器（ASF/WMA、APE、AIFF、WavPack、TAK、TTA）。
 *
 * 为什么需要：media3 的 28 个 Extractor 里没有这些容器，而播放链路是
 * Extractor→Decoder→AudioSink，容器分不出帧就喂不到解码器——
 * 这也是「加了 FFmpeg 解码器却仍然播不了 WMA」的原因。
 *
 * 实现要点：[ExtractorInput] 只能前进、不能随机 seek，而 avformat 解封装时经常要回退，
 * 所以先把整条流落到临时文件，再让 avformat 在真文件上跑——读、seek、算时长都天然正确，
 * 也不需要 JNI 上行回调。临时文件在 [release] 清理。
 *
 * 解码交给 media3 的 `FfmpegAudioRenderer`：这里只吐**压缩帧**，
 * 用 `audio/x-ffmpeg-<ffmpegCodecName>` 把 avformat 给出的精确 codec 名透传下去
 * （WMA 有 wmav1/wmav2/wmapro/wmalossless 四种，靠固定 mime 表分不出来）。
 */
class FfmpegExtractor : Extractor {

    private var output: ExtractorOutput? = null
    private var trackOutput: TrackOutput? = null
    private var demux: FfmpegDemux? = null
    private var tempFile: File? = null
    private var prepared = false
    private var ended = false
    private var packetCount = 0

    /**
     * 时间 → 原始流字节偏移 索引（喂给 media3 的 SeekMap）。
     * 必须给真实偏移：若都填 0，media3 的 `seekInsideBuffer` 恒为 false，
     * 每次 seek 都会强制从头重载（实测表现为：每个文件解封装两次、
     * 第二次卡在第 1 包、播放永久 BUFFERING）。
     */
    private val seekIndex = ArrayList<Pair<Long, Long>>()  // (timeUs, bytePos)

    // ---- sniff：认出 media3 不支持的容器 -----------------------------------

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(16)
        input.resetPeekPosition()
        val peeked = input.peek(head, 0, 16)
        input.resetPeekPosition()
        if (peeked < 4) return false
        return when {
            // ASF / WMA / WMV：GUID 30 26 B2 75 8E 66 CF 11 A6 D9 00 AA 00 62 CE 6C
            peeked >= 16 && head.copyOfRange(0, 16).contentEquals(ASF_GUID) -> true
            // Monkey's Audio
            head[0] == 'M'.code.toByte() && head[1] == 'A'.code.toByte() &&
                    head[2] == 'C'.code.toByte() && head[3] == ' '.code.toByte() -> true
            // WavPack
            head[0] == 'w'.code.toByte() && head[1] == 'v'.code.toByte() &&
                    head[2] == 'p'.code.toByte() && head[3] == 'k'.code.toByte() -> true
            // TTA
            head[0] == 'T'.code.toByte() && head[1] == 'T'.code.toByte() &&
                    head[2] == 'A'.code.toByte() && head[3] == '1'.code.toByte() -> true
            // TAK
            head[0] == 't'.code.toByte() && head[1] == 'B'.code.toByte() &&
                    head[2] == 'a'.code.toByte() && head[3] == 'K'.code.toByte() -> true
            // AIFF：FORM....AIFF / AIFC
            head[0] == 'F'.code.toByte() && head[1] == 'O'.code.toByte() &&
                    head[2] == 'R'.code.toByte() && head[3] == 'M'.code.toByte() -> true
            else -> false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    // ---- read：落临时文件 → 解封装 → 吐压缩帧 -------------------------------

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (ended) return Extractor.RESULT_END_OF_INPUT
        if (!prepared) {
            prepare(input)
            if (!prepared) return Extractor.RESULT_END_OF_INPUT
        }
        val d = demux ?: return Extractor.RESULT_END_OF_INPUT
        val pts = LongArray(1)
        val key = BooleanArray(1)
        val pos = LongArray(1)
        val packet = d.readPacket(pts, key, pos) ?: run {
            android.util.Log.d(TAG, "read: EOF，已吐 $packetCount 个包")
            ended = true
            return Extractor.RESULT_END_OF_INPUT
        }
        packetCount++
        if (pts[0] >= 0 && (seekIndex.isEmpty() || seekIndex.last().first != pts[0])) {
            seekIndex.add(pts[0] to pos[0])
        }
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT
        track.sampleData(ParsableByteArray(packet), packet.size)
        val timeUs = if (pts[0] >= 0) pts[0] else 0L
        track.sampleMetadata(
            timeUs,
            if (key[0]) C.BUFFER_FLAG_KEY_FRAME else 0,
            packet.size,
            0,
            null
        )
        return Extractor.RESULT_CONTINUE
    }

    /** 把整条流落到临时文件，再让 avformat 解封装并发出轨道。 */
    private fun prepare(input: ExtractorInput) {
        seekIndex.clear()
        packetCount = 0

        // 同一个文件会被 media3 起两次加载（实测 prepare:EOF ≈ 2:1），
        // 重下一遍会把起播延迟翻倍、看起来像"第 N 首卡死"。
        // 按流长度复用上次的临时文件即可避开（长度是下前就知道的，且不同曲目不会撞）。
        val streamLen = input.length
        val reused = if (streamLen > 0) synchronized(fileCache) { fileCache[streamLen] } else null
        val file: File
        if (reused != null && reused.exists() && reused.length() == streamLen) {
            android.util.Log.d(TAG, "prepare: 命中临时文件缓存 len=$streamLen")
            file = reused
        } else {
            file = File.createTempFile("xmusic-ff-", ".bin")
            try {
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf, 0, buf.size)
                        if (n == C.RESULT_END_OF_INPUT) break
                        if (n > 0) out.write(buf, 0, n)
                    }
                }
            } catch (t: Throwable) {
                file.delete()
                return
            }
            if (streamLen > 0) {
                synchronized(fileCache) {
                    // 只留 2 个：刚好覆盖「当前曲 + 下一首预载」，不占磁盘
                    val it = fileCache.entries.iterator()
                    while (it.hasNext() && fileCache.size >= 2) {
                        it.next().value.delete()
                        it.remove()
                    }
                    fileCache[streamLen] = file
                }
            }
        }
        tempFile = file

        val d = FfmpegDemux.open(file.absolutePath) ?: run {
            android.util.Log.w(TAG, "prepare: avformat 打开失败 size=${file.length()}")
            return
        }
        demux = d

        val codecName = d.codecName
        android.util.Log.d(
            TAG,
            "prepare: size=${file.length()} inputPos=${input.position} inputLen=${input.length} " +
                "codec=$codecName sr=${d.sampleRate} ch=${d.channelCount} " +
                "blockAlign=${d.blockAlign} dur=${d.durationUs} extra=${d.extraData?.size ?: -1}"
        )
        if (codecName.isEmpty()) {
            d.close()
            demux = null
            return
        }
        val builder = Format.Builder()
            // 透传精确 codec 名，见 FfmpegLibrary.getCodecName 的扩展注释
            .setSampleMimeType("audio/x-ffmpeg-$codecName")
            .setSampleRate(d.sampleRate)
            .setChannelCount(d.channelCount)
        // FFmpeg 的 ff_wma_init 要求 avctx->bit_rate > 0，否则返回 -1（被当成 EPERM）。
        // avformat 的 bit_rate 就是平均码率，用文件大小/时长等价算出来即可。
        if (d.durationUs > 0 && file.length() > 0) {
            builder.setAverageBitrate(((file.length() * 8_000_000L) / d.durationUs).toInt())
        }
        // initializationData 约定（本项目扩展）：
        //   [0] = avformat 的 extradata（WMA/ALAC 必需，可为 null）
        //   [1] = 4 字节 big-endian block_align（ASF/WMA 解码必需）
        //         + 4 字节 big-endian bits_per_coded_sample
        val extra = d.extraData ?: ByteArray(0)
        val aux = java.nio.ByteBuffer.allocate(8)
            .putInt(d.blockAlign)
            .putInt(d.bitsPerCodedSample)
            .array()
        builder.setInitializationData(listOf(extra, aux))
        val format = builder.build()

        val out = output ?: return
        val track = out.track(0, C.TRACK_TYPE_AUDIO)
        track.format(format)
        trackOutput = track
        out.endTracks()

        out.seekMap(object : SeekMap {
            override fun getDurationUs(): Long = d.durationUs
            override fun isSeekable(): Boolean = true
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                // 二分找 <= timeUs 的最近索引点，给出**真实字节偏移**
                if (seekIndex.isEmpty()) return SeekMap.SeekPoints(SeekPoint(0, 0))
                var lo = 0
                var hi = seekIndex.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) ushr 1
                    if (seekIndex[mid].first <= timeUs) lo = mid else hi = mid - 1
                }
                val p = seekIndex[lo]
                return SeekMap.SeekPoints(SeekPoint(p.first, p.second))
            }
        })
        prepared = true
    }

    override fun seek(positionUs: Long, timeUs: Long) {
        demux?.seek(timeUs)
        ended = false
    }

    override fun release() {
        demux?.close()
        demux = null
        // 缓存里的临时文件不删（下个 extractor 会复用）；未进缓存的自己收拾
        val f = tempFile
        if (f != null) {
            val cached = synchronized(fileCache) { fileCache.containsValue(f) }
            if (!cached) f.delete()
        }
        tempFile = null
        prepared = false
        ended = false
        packetCount = 0
        seekIndex.clear()
    }

    companion object {
        private const val TAG = "FfmpegExtractor"

        /** 流长度 → 临时文件。media3 会对同一个文件起两次加载，这里让第二次复用。 */
        private val fileCache = java.util.LinkedHashMap<Long, File>()

        private val ASF_GUID = byteArrayOf(
            0x30, 0x26.toByte(), 0xB2.toByte(), 0x75,
            0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
            0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(),
            0x00, 0x62, 0xCE.toByte(), 0x6C
        )

        /**
         * 只处理 media3 原生解不了的容器，把优先级排在
         * [androidx.media3.extractor.DefaultExtractorsFactory] 之前。
         * 这样 mp3/flac/m4a 等仍走 media3 原生路径（缓存/拖动/无缝都更成熟）。
         */
        @JvmStatic
        fun factory(): androidx.media3.extractor.ExtractorsFactory =
            androidx.media3.extractor.ExtractorsFactory { arrayOf(FfmpegExtractor()) }
    }
}
