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

/** 用户明确触发的 AI 对话请求；不参与逐字输入或后台自动发送。 */
class AiChatGenerator(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun suggestReplies(contextText: String, callback: (Result<List<String>>) -> Unit) {
        if (contextText.isBlank()) {
            callback(Result.failure(IllegalArgumentException("请先粘贴或输入聊天内容")))
            return
        }
        execute(callback) { config, key ->
            val system = """
你是中文聊天回复助手。根据用户提供的聊天上下文，给出恰好 15 条自然、可直接发送的中文回复。
分为五种风格，每种风格恰好 3 条：简洁自然、温柔体贴、轻松幽默、俏皮可爱、成熟得体。忠于上下文，不编造事实、承诺或关系，不确定时保持中性。
只输出严格 JSON：{"replies":["简洁自然1","简洁自然2","简洁自然3","温柔体贴1","温柔体贴2","温柔体贴3","轻松幽默1","轻松幽默2","轻松幽默3","俏皮可爱1","俏皮可爱2","俏皮可爱3","成熟得体1","成熟得体2","成熟得体3"]}。不要 Markdown、序号或解释。
""".trimIndent()
            val user = JSONObject().put("context", contextText.takeLast(4000)).toString()
            val content = request(config.baseUrl, config.model, key, listOf(
                JSONObject().put("role", "system").put("content", system),
                JSONObject().put("role", "user").put("content", user),
            ), 900)
            val clean = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val json = clean.substringAfter('{', "").substringBeforeLast('}', "")
            val array = JSONObject("{$json}").optJSONArray("replies") ?: JSONArray()
            val replies = buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.length in 1..300) add(value)
                    if (size == 15) break
                }
            }
            if (replies.size != 15) throw IllegalStateException("模型没有返回完整的 15 条回复（每种风格 3 条），请重试")
            replies
        }
    }

    /** messages 只包含本面板会话中的 role/content；调用前已由 UI 限制长度。 */
    fun chat(messages: List<Pair<String, String>>, callback: (Result<String>) -> Unit) {
        if (messages.isEmpty() || messages.last().first != "user" || messages.last().second.isBlank()) {
            callback(Result.failure(IllegalArgumentException("请输入你想问 AI 的内容")))
            return
        }
        execute(callback) { config, key ->
            val payload = buildList {
                add(JSONObject().put("role", "system").put(
                    "content",
                    "你是青简输入法里的中文 AI 对话助手。结合本次会话上下文，准确、自然地回答用户；需要代拟消息时给出可直接使用的文本。不要声称已发送消息，不要编造事实。"
                ))
                messages.takeLast(12).forEach { (role, content) ->
                    add(JSONObject().put("role", role).put("content", content.takeLast(4000)))
                }
            }
            request(config.baseUrl, config.model, key, payload, 1200).trim()
                .takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("模型没有返回内容，请重试")
        }
    }

    private fun <T> execute(
        callback: (Result<T>) -> Unit,
        block: (CloudConfig, String) -> T,
    ) {
        val config = runCatching { CloudConfig.load(context) }.getOrElse {
            callback(Result.failure(IllegalStateException("读取云端配置失败")))
            return
        }
        val key = config.apiKey()
        if (!config.ready || key.isNullOrBlank()) {
            callback(Result.failure(IllegalStateException("请先在「设置 → 云端大模型」填写接口地址、模型和 API Key")))
            return
        }
        executor.execute {
            val result = runCatching { block(config, key) }
            main.post { callback(result) }
        }
    }

    private fun request(
        baseUrl: String,
        model: String,
        apiKey: String,
        messages: List<JSONObject>,
        maxTokens: Int,
    ): String {
        val endpoint = baseUrl.trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.7)
            .put("max_tokens", maxTokens)
            .put("stream", false)
            .put("messages", JSONArray(messages))
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(message?.takeIf { !it.isNullOrBlank() } ?: "云端请求失败（HTTP $status）")
            }
            return JSONObject(responseText).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("模型没有返回内容")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val executor = Executors.newCachedThreadPool()
    }
}
