// avformat 解封装桥接（XMMusic 新增，非 androidx/media 原有）。
//
// 为什么要这层：media3 的 28 个 Extractor 里没有 ASF/APE/AIFF/WavPack/TAK/TTA，
// 而播放链路是 Extractor→Decoder→AudioSink，容器分不出帧就喂不到解码器。
// 这里借 FFmpeg 的 libavformat 解封装，把压缩帧交给 media3 的 FfmpegAudioRenderer 解码。
//
// 为什么打开的是**文件路径**而不是流：media3 的 ExtractorInput 只能前进、不能随机 seek，
// 而 avformat 解封装时经常要回退。所以由上层（FfmpegExtractor）先把整条流落到临时文件，
// 这里在真文件上跑 avformat —— 读、seek、算时长都天然正确，也不需要 JNI 上行回调。
//
// 许可证：本文件为 XMMusic 原创，随项目分发；链接的 FFmpeg 是 LGPL 构建
// （见 third_party/build_ffmpeg_lgpl.sh）。

#include <jni.h>
#include <cstring>
#include <cstdlib>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
}

namespace {

struct DemuxCtx {
    AVFormatContext* fmt = nullptr;
    int audioIndex = -1;
};

DemuxCtx* asCtx(jlong h) { return reinterpret_cast<DemuxCtx*>(h); }

/** AVStream 的时间基换算成微秒。 */
jlong streamTimeUs(const AVStream* st, int64_t ts) {
    if (ts == AV_NOPTS_VALUE) return -1;
    double us = ts * av_q2d(st->time_base) * 1000000.0;
    return (jlong) us;
}

}  // namespace

extern "C" {

// ---- 打开 / 关闭 ----------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeOpen(JNIEnv* env, jclass, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0;

    AVFormatContext* fmt = nullptr;
    int ret = avformat_open_input(&fmt, path, nullptr, nullptr);
    env->ReleaseStringUTFChars(jpath, path);
    if (ret < 0) return 0;

    ret = avformat_find_stream_info(fmt, nullptr);
    if (ret < 0) {
        avformat_close_input(&fmt);
        return 0;
    }
    int idx = av_find_best_stream(fmt, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (idx < 0) {
        avformat_close_input(&fmt);
        return 0;
    }
    auto* ctx = new DemuxCtx();
    ctx->fmt = fmt;
    ctx->audioIndex = idx;
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeClose(JNIEnv*, jclass, jlong h) {
    DemuxCtx* ctx = asCtx(h);
    if (!ctx) return;
    if (ctx->fmt) avformat_close_input(&ctx->fmt);
    delete ctx;
}

// ---- 音频流信息 -----------------------------------------------------------

static AVStream* audioStream(DemuxCtx* ctx) {
    return ctx && ctx->fmt ? ctx->fmt->streams[ctx->audioIndex] : nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetCodecName(JNIEnv* env, jclass, jlong h) {
    AVStream* st = audioStream(asCtx(h));
    if (!st) return nullptr;
    const char* name = avcodec_get_name(st->codecpar->codec_id);
    return env->NewStringUTF(name ? name : "");
}

JNIEXPORT jint JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetSampleRate(JNIEnv*, jclass, jlong h) {
    AVStream* st = audioStream(asCtx(h));
    return st ? st->codecpar->sample_rate : 0;
}

JNIEXPORT jint JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetChannels(JNIEnv*, jclass, jlong h) {
    AVStream* st = audioStream(asCtx(h));
    return st ? st->codecpar->ch_layout.nb_channels : 0;
}

JNIEXPORT jlong JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetDurationUs(JNIEnv*, jclass, jlong h) {
    DemuxCtx* ctx = asCtx(h);
    if (!ctx || !ctx->fmt) return -1;
    if (ctx->fmt->duration != AV_NOPTS_VALUE) {
        return (jlong) (ctx->fmt->duration * 1000000LL / AV_TIME_BASE);
    }
    AVStream* st = audioStream(ctx);
    return st ? streamTimeUs(st, st->duration) : -1;
}

/** 解码器需要的 extradata（WMA/ALAC 等），可能为 null。 */
JNIEXPORT jbyteArray JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetExtraData(JNIEnv* env, jclass, jlong h) {
    AVStream* st = audioStream(asCtx(h));
    if (!st || !st->codecpar->extradata || st->codecpar->extradata_size <= 0) return nullptr;
    jbyteArray arr = env->NewByteArray(st->codecpar->extradata_size);
    env->SetByteArrayRegion(arr, 0, st->codecpar->extradata_size,
                            reinterpret_cast<const jbyte*>(st->codecpar->extradata));
    return arr;
}

/** ASF/WMA 解码必需：块对齐（avcodec_open2 缺它会报 Invalid argument）。 */
JNIEXPORT jint JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetBlockAlign(JNIEnv*, jclass, jlong h) {
    AVStream* st = audioStream(asCtx(h));
    return st ? st->codecpar->block_align : 0;
}

JNIEXPORT jint JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeGetBitsPerCodedSample(JNIEnv*, jclass, jlong h) {
    AVStream* st = audioStream(asCtx(h));
    return st ? st->codecpar->bits_per_coded_sample : 0;
}

// ---- 读包 / 定位 ----------------------------------------------------------

/**
 * 读下一个音频包。返回压缩数据字节数组；到末尾返回 null；出错返回 null。
 * *ptsUs（out）：包的展示时间戳（微秒），无则 -1。
 * *isKeyFrame（out）：是否关键帧/可独立解码起点。
 * *bytePos（out）：包在**原始流**里的字节偏移（AVPacket.pos）。
 *   这个值必须传给 media3 的 SeekMap——否则它无法判断「某段是否已加载」，
 *   会反复从头重载、导致永久 BUFFERING。
 */
JNIEXPORT jbyteArray JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeReadPacket(
        JNIEnv* env, jclass, jlong h, jlongArray ptsUs, jbooleanArray isKeyFrame,
        jlongArray bytePos) {
    DemuxCtx* ctx = asCtx(h);
    AVStream* st = audioStream(ctx);
    if (!ctx || !st) return nullptr;

    AVPacket* pkt = av_packet_alloc();
    if (!pkt) return nullptr;

    int ret;
    while ((ret = av_read_frame(ctx->fmt, pkt)) >= 0) {
        if (pkt->stream_index == ctx->audioIndex) break;
        av_packet_unref(pkt);
    }
    if (ret < 0) {
        av_packet_free(&pkt);
        return nullptr;  // 读完或出错
    }

    jlong ts = streamTimeUs(st, pkt->dts != AV_NOPTS_VALUE ? pkt->dts : pkt->pts);
    env->SetLongArrayRegion(ptsUs, 0, 1, &ts);
    jboolean key = (pkt->flags & AV_PKT_FLAG_KEY) ? JNI_TRUE : JNI_FALSE;
    env->SetBooleanArrayRegion(isKeyFrame, 0, 1, &key);
    jlong pos = pkt->pos;
    env->SetLongArrayRegion(bytePos, 0, 1, &pos);

    jbyteArray out = env->NewByteArray(pkt->size);
    env->SetByteArrayRegion(out, 0, pkt->size, reinterpret_cast<const jbyte*>(pkt->data));
    av_packet_free(&pkt);
    return out;
}

/** 定位到时间戳（微秒）附近的关键帧。返回是否成功。 */
JNIEXPORT jboolean JNICALL
Java_com_xmgo_music_ffmpeg_FfmpegDemux_nativeSeek(JNIEnv* env, jclass, jlong h, jlong timeUs) {
    DemuxCtx* ctx = asCtx(h);
    AVStream* st = audioStream(ctx);
    if (!ctx || !st) return JNI_FALSE;
    int64_t ts = (int64_t) (timeUs / 1000000.0 / av_q2d(st->time_base));
    int ret = av_seek_frame(ctx->fmt, ctx->audioIndex, ts, AVSEEK_FLAG_BACKWARD);
    return ret >= 0 ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
