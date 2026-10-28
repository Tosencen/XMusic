import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xmgo.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.xmgo.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 正式签名：环境变量（CI secrets）优先，其次本地 keystore.properties（gitignored）。
        // 两者皆无时 storeFile 留空 → assembleRelease 失败（debug 构建不受影响），
        // 杜绝此前「CI 每次随机 debug keystore、各版本签名互不一致」的问题。
        create("release") {
            val env = System.getenv()
            val propsFile = rootProject.file("keystore.properties")
            val props = Properties()
            if (propsFile.exists()) propsFile.inputStream().use { props.load(it) }

            fun pick(name: String): String? =
                env[name]?.takeIf { it.isNotBlank() }
                    ?: props.getProperty(name)?.takeIf { it.isNotBlank() }

            pick("KEYSTORE_FILE")?.let { stored ->
                val f = File(stored)
                storeFile = if (f.isAbsolute) f else rootProject.file(stored)
            }
            storePassword = pick("KEYSTORE_PASSWORD")
            keyAlias = pick("KEY_ALIAS")
            keyPassword = pick("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        // Room 导出的 schema JSON 作为 androidTest assets，MigrationTestHelper 按数据库
        // 全限定类名目录（schemas/com.xmgo.music.data.local.MusicDatabase/2.json）读取
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

// Room schema 导出（配合 MusicDatabase exportSchema = true）：提交到版本库，
// 后续改表结构时用 MigrationTestHelper 校验迁移是否与旧 schema 兼容
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.material.kolor)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.rhino)
    implementation(libs.okhttp)
    implementation(libs.zxing)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.palette.ktx)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
