pluginManagement {
    val isCi = System.getenv("CI") == "true"
    repositories {
        // CI（GitHub Runner，海外网络）：官方源优先，避免依赖境内镜像的跨区可达性；
        // 本地：阿里云镜像优先（国内加速），官方源兜底——行为与原先一致
        if (isCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        if (!isCi) {
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}


dependencyResolutionManagement {
    val isCi = System.getenv("CI") == "true"
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (isCi) {
            google()
            mavenCentral()
            maven("https://jitpack.io")
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        if (!isCi) {
            google()
            mavenCentral()
            maven("https://jitpack.io")
        }
    }
}


rootProject.name = "XMMusic"
include(":app")
