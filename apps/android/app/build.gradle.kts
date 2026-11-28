import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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

// 构建期同步 Google 官方目录中当前已发布的全部 Noto 动画 Emoji；运行时只读 APK assets。
val downloadNotoEmojiLottie by tasks.registering {
    group = "qingjian"
    description = "Download the complete currently published Google Noto Emoji animation catalog"
    val outputDir = bundledAssets.map { it.dir("noto-emoji") }
    outputs.dir(outputDir)
    doLast {
        val target = outputDir.get().asFile
        target.mkdirs()

        val manifestConnection = URL("https://googlefonts.github.io/noto-emoji-animation/data/api.json").openConnection() as HttpURLConnection
        manifestConnection.connectTimeout = 15000
        manifestConnection.readTimeout = 30000
        val manifest = manifestConnection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        val idPattern = Regex("emoji_u([0-9a-fA-F_]+)")
        val ids: List<String> = idPattern.findAll(manifest)
            .map { match -> match.groupValues[1].lowercase() }
            .distinct()
            .toList()
        if (ids.size < 500) {
            throw GradleException("Noto Emoji manifest looks incomplete: only ${ids.size} animation IDs found")
        }

        // 删除旧的手工精选集，避免旧命名与完整目录产生重复或错误映射。
        target.listFiles()?.filter { it.extension == "json" && it.nameWithoutExtension !in ids }
            ?.forEach { it.delete() }

        val pool = Executors.newFixedThreadPool(8)
        try {
            val downloads = ids.map { id ->
                pool.submit(Callable<String?> {
                    val destination = target.resolve("$id.json")
                    if (destination.isFile && destination.length() > 100) return@Callable id
                    var lastError: Exception? = null
                    repeat(3) { attempt ->
                        val temporary = target.resolve("$id.json.part")
                        var connection: HttpURLConnection? = null
                        try {
                            connection = URL("https://fonts.gstatic.com/s/e/notoemoji/latest/$id/lottie.json").openConnection() as HttpURLConnection
                            connection.connectTimeout = 15000
                            connection.readTimeout = 30000
                            val status = connection.responseCode
                            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                                temporary.delete()
                                logger.lifecycle("Noto Emoji upstream asset unavailable (HTTP 404): $id")
                                return@Callable null
                            }
                            if (status !in 200..299) {
                                throw IOException("HTTP " + status + " for Noto Emoji " + id)
                            }
                            connection.inputStream.use { input ->
                                temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                            }
                            if (temporary.length() < 100) throw GradleException("Empty Lottie JSON for $id")
                            if (!temporary.renameTo(destination)) {
                                destination.delete()
                                if (!temporary.renameTo(destination)) throw GradleException("Cannot install Lottie asset $id")
                            }
                            return@Callable id
                        } catch (error: Exception) {
                            lastError = error
                            temporary.delete()
                            if (attempt < 2) Thread.sleep(500L * (attempt + 1))
                        } finally {
                            connection?.disconnect()
                        }
                    }
                    throw GradleException("Failed to download Noto Emoji " + id + " after retries: " +
                        (lastError?.javaClass?.simpleName ?: "Unknown") + ": " + (lastError?.message ?: "no details"), lastError)
                })
            }
            val available = mutableListOf<String>()
            val unavailable = mutableListOf<String>()
            val failures = mutableListOf<String>()
            downloads.forEachIndexed { index, future ->
                try {
                    val result = future.get()
                    if (result == null) unavailable += ids[index] else available += result
                } catch (error: Exception) {
                    failures += ids[index]
                    val cause = error.cause ?: error
                    logger.warn("Noto Emoji download failed: " + ids[index] + ": " +
                        cause.javaClass.simpleName + ": " + (cause.message ?: "no details"))
                }
            }
            target.resolve("catalog-ids.txt").writeText(available.joinToString("\n") + "\n")
            target.resolve("catalog-unavailable.txt").writeText(unavailable.joinToString("\n") + "\n")
            target.resolve("catalog-count.txt").writeText(available.size.toString())
            if (failures.isNotEmpty()) {
                throw GradleException("Noto Emoji download errors: " + available.size + "/" + ids.size +
                    " available; " + unavailable.size + " upstream 404; failed: " + failures.take(20))
            }
            logger.lifecycle("Bundled " + available.size + "/" + ids.size +
                " available Google Noto Emoji animations; " + unavailable.size +
                " upstream assets returned HTTP 404 (offline at runtime)")
        } finally {
            pool.shutdownNow()
        }

        target.resolve("LICENSE.txt").writeText(
            "Google Noto Emoji animations\n" +
            "Official catalog: https://googlefonts.github.io/noto-emoji-animation/\n" +
            "Manifest: https://googlefonts.github.io/noto-emoji-animation/data/api.json\n" +
            "Lottie files: https://fonts.gstatic.com/s/e/notoemoji/latest/<codepoints>/lottie.json\n" +
            "License: Creative Commons Attribution 4.0 International (CC BY 4.0)\n" +
            "https://creativecommons.org/licenses/by/4.0/\n"
        )
    }
}

// ochwpro MIT offline handwriting model: weights + character index are packaged in APK assets/data/handwriting.
val downloadOchwproModel by tasks.registering {
    group = "qingjian"
    description = "Bundle the official ochwpro offline handwriting model and labels"
    val target = layout.buildDirectory.dir("ochwpro-model")
    outputs.dir(target)
    outputs.upToDateWhen { false }
    doLast {
        val dir = target.get().asFile
        dir.mkdirs()
        fun download(path: String, file: String, expectedBytes: Long) {
            val destination = dir.resolve(file)
            if (destination.isFile && destination.length() == expectedBytes) return
            destination.delete()
            val url = URL("https://raw.githubusercontent.com/ximeiorg/ochwpro/main/" + path)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            try {
                if (connection.responseCode !in 200..299) throw GradleException("ochwpro download failed: " + path + " HTTP " + connection.responseCode)
                connection.inputStream.use { input -> destination.outputStream().buffered().use { output -> input.copyTo(output) } }
            } finally { connection.disconnect() }
            if (destination.length() != expectedBytes) throw GradleException("ochwpro asset size mismatch: " + file + " expected=" + expectedBytes + " actual=" + destination.length())
        }
        download("ochw-wasm/ochw_model_fp16.safetensors", "ochw_model_fp16.safetensors", 3451280L)
        download("ochw-wasm/char_index.json", "char_index.json", 51317L)
        download("LICENSE", "LICENSE-MIT.txt", 1063L)
        logger.lifecycle("Bundled ochwpro offline handwriting model (" + dir.resolve("ochw_model_fp16.safetensors").length() + " bytes)")
    }
}

// 让 Sync 任务把模型复制进最终 assets/data，避免与 Noto/其他随包数据发生目录竞争。
stageData.configure {
    dependsOn(downloadOchwproModel)
    from(layout.buildDirectory.dir("ochwpro-model")) { into("handwriting") }
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
