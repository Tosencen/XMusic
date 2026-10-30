// AGP 已由 :app 引入并在 classpath 上，这里不能再指定版本号
// （Gradle 会报 "plugin is already on the classpath with an unknown version"）
plugins {
    id("com.android.library")
}

/**
 * FFmpeg 扩展模块。
 *
 * 内含两部分：
 * 1. `androidx.media3.decoder.ffmpeg.*` —— 从 androidx/media 的
 *    `libraries/decoder_ffmpeg` 原样引入（Apache-2.0，许可证头保留），提供
 *    `FfmpegAudioRenderer`/`FfmpegAudioDecoder`，负责**解码**。
 * 2. `com.xmgo.music.ffmpeg.*` —— 本项目新增，走 avformat 负责**解封装**。
 *    为什么需要：media3 的 28 个 Extractor 里没有 ASF/APE/AIFF/WavPack/TAK，
 *    而链路是 Extractor→Decoder→AudioSink，容器分不出帧就喂不到解码器。
 *
 * 链接的是 `third_party/ffmpeg-prebuilt/` 下自编的 **LGPL** FFmpeg 静态库
 * （configure 带 --disable-gpl --disable-nonfree），不是 GPL 版本。
 */
android {
    namespace = "com.xmgo.music.ffmpeg"
    compileSdk = 37
    // 钉住 NDK 27：① FFmpeg 静态库就是用它编的，同版本最稳；
    // ② 本机 NDK 28.2 装得不完整（缺 source.properties），AGP 会自动选最新 NDK 然后失败
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.txt")

        // 只出货 arm64-v8a：真机与当前模拟器都是 arm64，避免打包无用 ABI
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // media3 原版 decoder_ffmpeg 模块声明的三个依赖（见其 build.gradle.kts）
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.media3:media3-decoder:1.11.1")
    implementation(libs.androidx.media3.exoplayer)
}
