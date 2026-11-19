package app.qingjian.inputmethod.localai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

/**
 * 第一阶段本地模型管理：手动下载、大小与 SHA-256 校验、后台加载及离线冒烟测试。
 * 模型保存在 filesDir，不进入 APK；引擎只在测试期间加载，结束后释放内存。
 */
object LocalAiModelManager {
    const val MODEL_FILE_NAME = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
    private const val EXPECTED_SIZE = 344_686_592L
    private const val EXPECTED_SHA256 =
        "96491bb0f78fa68098bf8f1d3628194c97041aff7894f8cc29303b3acdb877a9"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 60_000

    private val downloadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qingjian-local-ai-download").apply { isDaemon = true }
    }
    private val inferenceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "qingjian-local-ai-inference").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun modelFile(context: Context): File =
        File(File(context.applicationContext.filesDir, "local-ai"), MODEL_FILE_NAME)

    fun isInstalled(context: Context): Boolean {
        val file = modelFile(context)
        return file.isFile && file.length() == EXPECTED_SIZE
    }

    fun download(
        context: Context,
        onProgress: (Int) -> Unit,
        onComplete: (Result<File>) -> Unit,
    ) {
        val appContext = context.applicationContext
        downloadExecutor.execute {
            val directory = File(appContext.filesDir, "local-ai")
            val destination = File(directory, MODEL_FILE_NAME)
            val partial = File(directory, MODEL_FILE_NAME + ".part")
            val result = runCatching {
                directory.mkdirs()
                check(directory.isDirectory) { "无法创建模型目录" }
                if (destination.isFile && destination.length() == EXPECTED_SIZE &&
                    sha256(destination) == EXPECTED_SHA256
                ) {
                    return@runCatching destination
                }
                destination.delete()
                partial.delete()

                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Qingjian-Android")
                }
                try {
                    connection.connect()
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "模型服务器返回 HTTP " + connection.responseCode
                    }
                    val total = connection.contentLengthLong
                    if (total > 0L) {
                        check(total == EXPECTED_SIZE) { "模型文件大小与官方清单不一致" }
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    var lastPercent = -1
                    connection.inputStream.buffered().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                copied += count
                                val percent = ((copied * 100L) / EXPECTED_SIZE).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    mainHandler.post { onProgress(percent) }
                                }
                            }
                            output.flush()
                        }
                    }
                    check(copied == EXPECTED_SIZE) { "下载不完整：收到 " + copied + " 字节" }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                    check(actualHash.equals(EXPECTED_SHA256, ignoreCase = true)) {
                        "SHA-256 校验失败，已删除损坏文件"
                    }
                    check(partial.renameTo(destination)) { "模型文件保存失败" }
                    destination
                } finally {
                    connection.disconnect()
                }
            }
            if (result.isFailure) partial.delete()
            mainHandler.post { onComplete(result) }
        }
    }

    fun runOfflineSmokeTest(context: Context, onComplete: (Result<String>) -> Unit) {
        val appContext = context.applicationContext
        inferenceExecutor.execute {
            val result = runCatching {
                val file = modelFile(appContext)
                check(file.isFile && file.length() == EXPECTED_SIZE) {
                    "模型文件不存在或大小不正确，请重新下载"
                }
                val prompt = "请只用简体中文回复：青简本地 AI 已成功运行。"
                val gpuResult = runCatching {
                    inferOnce(file, appContext.cacheDir, Backend.GPU(), prompt)
                }
                gpuResult.getOrElse {
                    inferOnce(file, appContext.cacheDir, Backend.CPU(), prompt)
                }.also { reply ->
                    check(reply.isNotBlank()) { "模型没有返回文本" }
                }
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun deleteModel(context: Context): Boolean {
        val file = modelFile(context)
        val partial = File(file.parentFile, MODEL_FILE_NAME + ".part")
        partial.delete()
        return !file.exists() || file.delete()
    }

    private fun inferOnce(file: File, cacheDir: File, backend: Backend, prompt: String): String {
        var engine: Engine? = null
        var conversation: Conversation? = null
        try {
            val candidate = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend,
                    cacheDir = cacheDir.absolutePath,
                ),
            )
            // initialize 可能阻塞数秒；此方法只在专用后台线程执行。
            // 若 initialize 抛错，不调用 close；LiteRT-LM 要求仅关闭已成功初始化的 Engine。
            candidate.initialize()
            engine = candidate
            conversation = candidate.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "你是青简输入法的本地中文助手。回答简洁、准确，只输出用户需要的内容。",
                    ),
                ),
            )
            val output = StringBuilder()
            runBlocking {
                conversation.sendMessageAsync(prompt).collect { chunk ->
                    output.append(chunk.toString())
                }
            }
            return output.toString().trim()
        } finally {
            conversation?.close()
            engine?.close()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
