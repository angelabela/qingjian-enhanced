pluginManagement {
    repositories {
        google()
        // Google-hosted Maven Central mirror: keep CI builds resilient when
        // repo.maven.apache.org is temporarily unavailable or rate-limited.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Fallback mirror for Maven Central artifacts used by AGP/Kotlin.
        maven {
            url = uri("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        // Binary Eye 使用的 ZXing-C++ Android 绑定发布在 JitPack。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "qingjian-android"
include(":app")
