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
    }
}

rootProject.name = "qingjian-android"
include(":app")
