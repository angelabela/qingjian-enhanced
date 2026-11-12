package app.qingjian.inputmethod.cloud

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** 按用户主动触发的上下文生成多风格表达；不参与逐字云联想请求。 */
data class AiExpressionGroup(val title: String, val items: List<String>)

class AiExpressionGenerator(private val context: android.content.Context) {
    private val main = Handler(Looper.getMainLooper())

    fun generate(source: String, callback: (Result<List<AiExpressionGroup>>) -> Unit) {
        val config = runCatching { CloudConfig.load(context) }.getOrElse {
            callback(Result.failure(IllegalStateException("读取云端配置失败")))
            return
        }
        val key = config.apiKey()
        if (config.baseUrl.isBlank() || config.model.isBlank() || key.isNullOrBlank()) {
            callback(Result.failure(IllegalStateException("请先在「设置 → 云端大模型」填写接口地址、模型和 API Key")))
            return
        }
        if (source.isBlank()) {
            callback(Result.failure(IllegalArgumentException("请先在输入框输入一段文字，再使用 AI 情境表达")))
            return
        }
        executor.execute {
            val result = runCatching { request(config.baseUrl, config.model, key, source.trim()) }
            main.post { callback(result) }
        }
    }

    private fun request(baseUrl: String, model: String, apiKey: String, source: String): List<AiExpressionGroup> {
        val endpoint = baseUrl.trimEnd('/').let {
            if (it.endsWith("/chat/completions")) it else "$it/chat/completions"
        }
        val system = """
你是中文输入法里的「AI情境表达」助手。用户给出一段正在输入的文字，请理解真实意图、上下文和语气，改写成自然、可直接发送的完整中文消息。
必须输出严格 JSON，不要 Markdown，不要解释，结构为：
{"groups":[{"title":"直白自然","items":["...","...","..."]},{"title":"温柔走心","items":["...","...","..."]},{"title":"软萌撒娇","items":["...","...","..."]},{"title":"轻松调皮","items":["...","...","..."]},{"title":"礼貌高情商","items":["...","...","..."]}]}
必须尽量包含以上 5 个分类，每类最多 3 条、尽量给满 3 条。不同句子要有明显表达差异，不要只替换一两个词凑数。忠于用户原意，不擅自添加事实、承诺、关系或过度亲密内容；不确定对象关系时用中性称呼。每条都是可直接发送的句子，避免标题、序号、引号和解释。不要重复输入原句本身。
""".trimIndent()
        val user = JSONObject()
            .put("context", source.takeLast(500))
            .put("task", "根据这段上下文生成多风格表达")
            .toString()
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.85)
            .put("max_tokens", 1800)
            .put("stream", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
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
                val message = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException(message?.takeIf { !it.isNullOrBlank() } ?: "云端请求失败（HTTP $status）")
            }
            val content = JSONObject(responseText)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("模型没有返回表达内容")
            val jsonText = content.substringAfter('{', "").let { if (it.isEmpty()) "" else "{$it" }
            val groups = JSONObject(jsonText).optJSONArray("groups") ?: JSONArray()
            val seen = mutableSetOf<String>()
            val result = mutableListOf<AiExpressionGroup>()
            for (i in 0 until groups.length()) {
                val group = groups.optJSONObject(i) ?: continue
                val title = group.optString("title").trim().takeIf { it.isNotEmpty() } ?: continue
                val array = group.optJSONArray("items") ?: continue
                val items = buildList {
                    for (j in 0 until array.length()) {
                        val item = array.optString(j).replace("\\n", " ").trim()
                        if (item.length in 2..120 && seen.add(item)) add(item)
                        if (size >= 3) break
                    }
                }
                if (items.isNotEmpty()) result += AiExpressionGroup(title, items)
            }
            if (result.isEmpty()) throw IllegalStateException("模型返回内容格式不正确，请再试一次")
            result
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        val executor = Executors.newCachedThreadPool()
    }
}
