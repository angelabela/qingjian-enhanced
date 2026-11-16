package app.qingjian.inputmethod.cloud

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * 只在一段拼音输入暂时停顿后，根据光标前文从本地候选中挑选一个最合适的词。
 * 返回值必须与原候选逐字相等；模型生成新词、改写或返回 NONE 都不会改变候选顺序。
 */
class CandidateContextReranker(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun rerank(
        before: String,
        pinyin: String,
        candidates: List<String>,
        callback: (String?) -> Unit,
    ) {
        if (before.isBlank() || pinyin.isBlank() || candidates.size < 2) {
            callback(null)
            return
        }
        executor.execute {
            val selected = runCatching {
                val config = CloudConfig.load(context)
                val key = config.apiKey()
                if (!config.ready || key.isNullOrBlank()) return@runCatching null
                val system = """
你是安卓输入法的候选词重排器。你的唯一任务：从给定的候选列表中，选出最符合上文语境的一个。

硬性约束：
1. 你只能输出候选列表中原有的词，不得改写、拼接或生成任何新内容。
2. 如果候选列表中没有任何一个能通顺接在上文后面，只输出：NONE
3. 不要解释，不要标点，不要换行。只输出一个词或 NONE。

判断依据（按优先级）：
1. 语义通顺度：哪个候选与上文构成自然搭配
2. 用户习惯：如果候选中有明显是用户自定义的词（如人名、术语），优先考虑
3. 不要因为拼音相似而选，拼音匹配是上游的事

输入是 JSON，字段为 before（光标前最近上下文）、pinyin（当前拼音）、candidates（原始候选数组）。pinyin 只用于理解输入阶段，不得凌驾于语义判断。候选必须原样选择。
""".trimIndent()
                val user = JSONObject()
                    .put("before", before.takeLast(20))
                    .put("pinyin", pinyin.takeLast(64))
                    .put("candidates", JSONArray(candidates))
                    .toString()
                val endpoint = config.baseUrl.trimEnd('/').let {
                    if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
                }
                val body = JSONObject()
                    .put("model", config.model)
                    .put("temperature", 0.0)
                    .put("max_tokens", 32)
                    .put("stream", false)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6_000
                    readTimeout = 9_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                try {
                    connection.outputStream.use {
                        it.write(body.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) return@runCatching null
                    val content = JSONObject(response)
                        .optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content")
                        ?.trim()
                        ?: return@runCatching null
                    if (content == "NONE") null else candidates.firstOrNull { it == content }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            main.post { callback(selected) }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
