import java.net.URL
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 仓库根（ime/）：Rust workspace 与随包数据都从这里取。
val repoRoot = rootDir.resolve("../..").canonicalFile
val nativeLibs = layout.buildDirectory.dir("native/jniLibs")
val bundledAssets = layout.buildDirectory.dir("bundled/assets")

// 版本号取 core/Cargo.toml；versionCode = 主*10000 + 次*100 + 修订，-dev 与正式版同号
val appVersion = rootDir.resolve("core/Cargo.toml").readLines()
    .first { it.startsWith("version = ") }
    .substringAfter('"').substringBefore('"')
val appVersionCode = appVersion.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

// 发版 CI 从 Secrets 注入，见 docs/notes/release.md
val releaseKeystore = System.getenv("QINGJIAN_ANDROID_KEYSTORE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "app.qingjian.inputmethod"
    compileSdk = 36
    ndkVersion = "29.0.13599879"

    defaultConfig {
        applicationId = "app.qingjian.inputmethod"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters += "arm64-v8a" }
    }

    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("QINGJIAN_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QINGJIAN_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("QINGJIAN_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // 手机上装着发版签名的包时并排装调试版（-PsideBySide），不用卸载、不丢数据
            if (project.hasProperty("sideBySide")) applicationIdSuffix = ".dev"
        }
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].apply {
        jniLibs.srcDir(nativeLibs)
        assets.srcDir(bundledAssets)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// Rust 侧：cargo ndk 编出 libqingjian_android.so，放进 jniLibs 源目录。要先 `cargo install cargo-ndk`。
val cargoNdk by tasks.registering(Exec::class) {
    group = "qingjian"
    description = "cargo ndk 编译 qingjian-android（arm64-v8a，release）"
    workingDir = repoRoot
    val ndkDir = android.sdkDirectory.resolve("ndk/${android.ndkVersion}")
    environment("ANDROID_NDK_HOME", ndkDir.absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "--platform", "26",
        "-o", nativeLibs.get().asFile.absolutePath,
        "build", "-p", "qingjian-android", "--release", "--locked",
    )
    // 增量交给 cargo 判断，这里每次都跑
    outputs.upToDateWhen { false }
}

// 随包数据：data/generated/ 的产品数据优先，没有就退回 assets/sample/ 样例（与 mac 的 bundle.sh 同一规矩）。
val stageData by tasks.registering(Sync::class) {
    group = "qingjian"
    description = "把随包数据放进 assets/data/"
    val generated = repoRoot.resolve("data/generated")
    val hasGenerated = generated.resolve("dict.qj").isFile
    into(bundledAssets.map { it.dir("data") })
    if (hasGenerated) {
        from(generated) {
            // 学习语言可选，几门语言的释义表都带上（共约 65 MB）
            include("dict.qj", "lm.qj", "glossary-*.qj", "english.tsv")
        }
        // 随包领域词库（与 PC 同一份），词库页里勾选
        from(generated.resolve("dicts")) { into("dicts") }
        // 本地含章·通变 P2C 模型：Android 也使用与桌面端相同的拼音到整句生成能力。
        from(repoRoot.resolve("data/models/hanzhang-tongbian")) {
            include("hanzhang-tongbian-small.qjm")
        }
    } else {
        from(repoRoot.resolve("assets/sample")) { include("dict.tsv", "glossary-en.tsv", "english.tsv") }
    }
    from(repoRoot.resolve("assets/emoji")) { include("emoji-zh.tsv", "emoji-en.tsv") }
    // 词汇等级表（英语 CEFR、日语 JLPT），生词本按级筛与统计页按级分用
    from(repoRoot.resolve("assets/levels")) { include("levels-*.tsv") }
    // 五笔码表（形码方案用，与 PC 随包的同一份），解出来仍叫 wubi86.tsv
    from(repoRoot.resolve("assets/schemes/wubi86")) {
        include("table.tsv")
        rename { "wubi86.tsv" }
    }
}

// 构建期预取常见 Noto 动画 Emoji；运行时只读 APK assets，不发起网络请求。
val downloadNotoEmojiLottie by tasks.registering {
    group = "qingjian"
    description = "Download a compact offline set of Google Noto Emoji Lottie animations"
    val outputDir = bundledAssets.map { it.dir("noto-emoji") }
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile
        target.mkdirs()
        val emoji = listOf(
            "😀","😃","😄","😁","😆","😅","😂","🤣","😊","😍","🥰","😘","😎","🤔","😭","😢",
            "😡","😱","😴","😇","🤗","👍","👎","🙏","👏","🙌","❤️","💔","💕","💯","🔥","🎉",
            "⭐","🌈","☀️","🌙","☁️","🌧️","❄️","⚡","🚀","🎁","🍀","🐱","🐶","🏹","⚽","🏀",
            "🍎","🍕","☕","🎵","🎶","💡","✅","❌","⚠️","❤️‍🔥","🫶","🫡","🫠","🦄","🐼","🌸"
        )
        emoji.distinct().forEach { glyph ->
            val codepoints = glyph.codePoints().toArray().joinToString("-") { it.toString(16) }
            val destination = target.resolve("$codepoints.json")
            if (!destination.isFile) {
                try {
                    val url = URL("https://fonts.gstatic.com/s/e/notoemoji/latest/$codepoints/lottie.json")
                    val connection = url.openConnection()
                    connection.connectTimeout = 8000
                    connection.readTimeout = 12000
                    connection.getInputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
                    if (destination.length() < 100) destination.delete()
                } catch (error: Exception) {
                    logger.warn("Noto Emoji Lottie unavailable for $glyph ($codepoints): ${error.message}")
                    destination.delete()
                }
            }
        }
        // Attribution/license is bundled as a readable text asset as well as shown in About.
        target.resolve("LICENSE.txt").writeText(
            "Google Noto Emoji animations\nSource: https://googlefonts.github.io/noto-emoji-animation/\n" +
            "Lottie files: https://fonts.gstatic.com/s/e/notoemoji/latest/<codepoints>/lottie.json\n" +
            "License: Creative Commons Attribution 4.0 International (CC BY 4.0)\n" +
            "https://creativecommons.org/licenses/by/4.0/\n"
        )
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdk, stageData, downloadNotoEmojiLottie)
}


dependencies {
    // 本地 AI：LiteRT-LM 0.19.0 支持 Qwen3-0.6B INT4 的 Android GPU/CPU 推理。
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
    // 260：持续相机预览与本机条码识别（不上传扫描内容）
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // 拍照识图：中文与英文文字在本机识别，不上传照片。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // 剪贴板搜索：预生成逐字拼音首字母，支持多音字。
    implementation("com.belerweb:pinyin4j:2.5.1")
    // 剪贴板文字二维码生成（本机处理，不上传内容）。
    implementation("com.airbnb.android:lottie:6.6.7")
    implementation("com.google.zxing:core:3.5.3")
    // Binary Eye 同源的 ZXing-C++ Android 官方绑定（Apache-2.0）。
    implementation("io.github.zxing-cpp:android:3.1.1")
}
