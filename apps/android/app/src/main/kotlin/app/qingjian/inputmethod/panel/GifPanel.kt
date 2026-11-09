package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import app.qingjian.inputmethod.cloud.GifConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * GIPHY 搜索面板。缩略图只用于预览；点选后下载原始 GIF 到应用私有目录，再交给输入法的图片上屏通道。
 */
class GifPanel(
    context: Context,
    private val actions: PanelActions,
    private val initialQuery: String = "",
) : FrameLayout(context) {
    private val query = EditText(context).apply {
        hint = "搜索表情包，例如：谢谢"
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        textSize = 16f
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    private val search = Button(context).apply { text = "搜索" }
    private val status = TextView(context).apply {
        text = "输入关键词，搜索 GIF 动图"
        textSize = 13f
        setTextColor(Color.GRAY)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private val results = GridLayout(context).apply { columnCount = 3 }
    private val attribution = TextView(context).apply {
        text = "Powered by GIPHY"
        textSize = 11f
        gravity = Gravity.CENTER
        setTextColor(Color.GRAY)
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }
    private var searching = false

    init {
        setBackgroundColor(Color.rgb(248, 248, 250))
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(4))
            addView(query, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(search, LinearLayout.LayoutParams(dp(76), dp(48)))
        }
        val list = ScrollView(context).apply {
            isFillViewport = true
            addView(results, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(status)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(attribution)
        }
        addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        search.setOnClickListener { search(query.text.toString()) }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { search(query.text.toString()); true } else false
        }
        if (GifConfig.load(context).isNullOrBlank()) {
            status.text = "请先到「设置 → 云端大模型」填写 GIPHY API Key"
            search.isEnabled = false
        } else if (initialQuery.isNotBlank()) {
            query.setText(initialQuery.take(64))
            post { search(initialQuery) }
        }
    }

    private fun search(term: String) {
        val keyword = term.trim()
        if (keyword.isEmpty() || searching) return
        val key = GifConfig.load(context)
        if (key.isNullOrBlank()) {
            status.text = "请先设置 GIPHY API Key"
            return
        }
        searching = true
        search.isEnabled = false
        status.text = "正在搜索…"
        results.removeAllViews()
        thread(name = "qingjian-giphy-search") {
            val outcome = runCatching {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val endpoint = "https://api.giphy.com/v1/gifs/search?api_key=${URLEncoder.encode(key, "UTF-8")}&q=$encoded&limit=24&rating=pg"
                val json = request(endpoint, 2 * 1024 * 1024).toString(Charsets.UTF_8)
                val data = JSONObject(json).getJSONArray("data")
                val items = (0 until data.length()).mapNotNull { index ->
                    val images = data.getJSONObject(index).getJSONObject("images")
                    val original = images.getJSONObject("original").getString("url")
                    val preview = images.optJSONObject("fixed_width_small")?.optString("url")
                        ?.takeIf { it.startsWith("https://") } ?: original
                    if (original.startsWith("https://")) preview to original else null
                }
                items
            }
            post {
                searching = false
                search.isEnabled = true
                val items = outcome.getOrElse {
                    status.text = "搜索失败，请检查网络和 GIPHY API Key"
                    emptyList()
                }
                if (items.isEmpty() && outcome.isSuccess) status.text = "没有找到相关 GIF，试试其他关键词"
                else if (items.isNotEmpty()) status.text = "找到 ${items.size} 个结果，点选即可发送"
                items.forEach { (preview, original) -> addGif(preview, original) }
            }
        }
    }

    private fun addGif(preview: String, original: String) {
        val tile = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "GIPHY GIF"
            }
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, dp(104)))
            setOnClickListener { downloadAndSend(original) }
        }
        val lp = GridLayout.LayoutParams().apply {
            width = 0
            height = dp(108)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        results.addView(tile, lp)
        thread(name = "qingjian-giphy-preview") {
            val bitmap = runCatching { val bytes = request(preview, 3 * 1024 * 1024); BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            tile.post {
                if (bitmap != null && tile.childCount > 0) (tile.getChildAt(0) as ImageView).setImageBitmap(bitmap)
            }
        }
    }

    private fun downloadAndSend(url: String) {
        status.text = "正在下载动图…"
        search.isEnabled = false
        thread(name = "qingjian-giphy-download") {
            val file = runCatching {
                val bytes = request(url, 15 * 1024 * 1024)
                val dir = File(context.filesDir, "user/giphy")
                if (!dir.exists() && !dir.mkdirs()) error("无法创建目录")
                val target = File(dir, "giphy-${System.currentTimeMillis()}.gif")
                target.writeBytes(bytes)
                target
            }
            post {
                search.isEnabled = true
                file.onSuccess { actions.commitImage(it.absolutePath) }
                    .onFailure { status.text = "动图下载失败，请重试" }
            }
        }
    }

    private fun request(address: String, maxBytes: Int): ByteArray {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Qingjian-Android")
        return connection.run {
            try {
                connect()
                if (responseCode !in 200..299) error("HTTP $responseCode")
                inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) error("文件过大")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { disconnect() }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
