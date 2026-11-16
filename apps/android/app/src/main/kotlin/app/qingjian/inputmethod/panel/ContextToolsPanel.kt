package app.qingjian.inputmethod.panel

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.cloud.AiChatGenerator
import app.qingjian.inputmethod.theme.Palette
import java.math.BigDecimal
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 用户主动打开的上下文工具；本面板初始化和识别过程绝不发起云端请求。 */
class ContextToolsPanel(
    private val context: Context,
    private val palette: Palette,
    headerHeight: Float,
    private val source: String,
    private val hasSelection: Boolean,
    private val generator: AiChatGenerator,
    private val onInsert: (String) -> Unit,
    private val onReplaceSelection: (String) -> Boolean,
    private val onCopy: (String) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val body = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(14))
    }
    private val status = TextView(context).apply {
        textSize = 13f
        setTextColor(palette.gloss)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(10), dp(6), dp(10))
    }
    private val result = TextView(context).apply {
        textSize = 15f
        setTextColor(palette.text)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(palette.surface, 12)
        visibility = View.GONE
    }
    private var generated = ""
    private var requestId = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.board)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
            background = rounded(palette.surface, 14)
            addView(TextView(context).apply {
                text = "‹"
                textSize = 32f
                gravity = Gravity.CENTER
                setTextColor(palette.text)
                setOnClickListener { onClose() }
            }, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
            addView(TextView(context).apply {
                text = "文字上下文工具"
                textSize = 17f
                setTextColor(palette.text)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(context).apply {
                text = "本地识别 · 按需云端"
                textSize = 10f
                setTextColor(palette.cloud)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, headerHeight.toInt().coerceAtLeast(dp(48))))

        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(TextView(context).apply {
            text = if (source.isBlank()) "请先在输入框中输入文字，或选中一段文字后再打开工具。" else source
            textSize = 15f
            setTextColor(palette.text)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(palette.surface, 12)
        })
        body.addView(TextView(context).apply {
            text = if (hasSelection) "已识别选中文字" else "已读取光标附近的少量文字"
            textSize = 11f
            setTextColor(palette.gloss)
            setPadding(dp(4), dp(7), dp(4), dp(5))
        })
        body.addView(status)
        body.addView(result)
        if (source.isNotBlank()) buildActions()
    }

    private fun buildActions() {
        val value = source.trim()
        val url = URL_REGEX.find(value)?.value
        val phone = PHONE_REGEX.find(value)?.value
        val email = EMAIL_REGEX.find(value)?.value
        val date = DATE_REGEX.find(value)?.value
        val money = MONEY_REGEX.find(value)?.value
        val isEnglish = value.any { it in 'A'..'Z' || it in 'a'..'z' }
        val isAddress = listOf("省", "市", "区", "县", "镇", "乡", "街道", "路", "巷", "号", "栋", "单元", "室").any { value.contains(it) }
        val isCode = value.contains("{") || value.contains("}") || value.contains("fun ") ||
            value.contains("class ") || value.contains("=>") || value.contains("def ")

        section("识别到的内容")
        when {
            url != null -> {
                action("打开网址") {
                    val address = if (url.startsWith("http", true)) url else "https://$url"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { showStatus("无法打开这个网址") }
                }
                action("复制网址") { onCopy(url); showStatus("已复制网址") }
                action("提取域名") {
                    val host = runCatching { URI(if (url.startsWith("http")) url else "https://$url").host }.getOrNull()
                    if (host.isNullOrBlank()) showStatus("无法识别域名") else { onCopy(host); showStatus("已复制域名：$host") }
                }
            }
            phone != null -> {
                action("拨打电话") {
                    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { showStatus("无法打开拨号界面") }
                }
                action("复制号码") { onCopy(phone); showStatus("已复制号码") }
                action("格式化号码") {
                    val digits = phone.filter { it.isDigit() }
                    val formatted = if (digits.length == 11) digits.chunked(3).joinToString(" ") else phone
                    showResult(formatted)
                }
            }
            email != null -> {
                val valid = EMAIL_REGEX.matches(email)
                showStatus(if (valid) "邮箱格式检查通过" else "邮箱格式可能不正确")
                action("复制邮箱") { onCopy(email); showStatus("已复制邮箱地址") }
            }
            date != null -> {
                action("转换日期格式") {
                    val converted = normalizeDate(date)
                    if (converted == null) showStatus("暂不支持这个日期格式") else showResult(converted)
                }
                action("复制日期") { onCopy(date); showStatus("已复制日期") }
            }
            money != null -> {
                action("转换人民币大写") {
                    val converted = moneyToChinese(money)
                    if (converted == null) showStatus("金额格式不正确或超出转换范围") else showResult(converted)
                }
                action("复制金额") { onCopy(money); showStatus("已复制金额") }
            }
            isAddress -> {
                action("整理地址") {
                    val formatted = value.replace(Regex("[ \\t]+"), "")
                        .replace(Regex("(省|市|区|县|镇|乡|街道|路|巷|号|栋|单元|室)"), "$1 ")
                        .trim()
                    showResult(formatted)
                }
                action("复制地址") { onCopy(value); showStatus("已复制地址") }
            }
            isCode -> {
                action("整理代码缩进") { showResult(formatCode(value)) }
                action("复制代码") { onCopy(value); showStatus("已复制代码") }
            }
            isEnglish -> {
                action("英文语法检查") { requestAi("grammar") }
                action("翻译成中文") { requestAi("translate") }
                action("复制原文") { onCopy(value); showStatus("已复制") }
            }
            else -> {
                action("整理文本空格和标点") { showResult(value.replace(Regex("[ \\t]+"), " ").replace(Regex(" +([，。！？；：])"), "$1")) }
                action("复制文本") { onCopy(value); showStatus("已复制") }
            }
        }

        section("智能续写")
        action("根据当前内容续写") { requestAi("continue") }
        body.addView(TextView(context).apply {
            text = "续写结果不会自动写入输入框。请先查看结果，再选择插入、替换选中文字或继续生成。"
            textSize = 11f
            setTextColor(palette.gloss)
            setPadding(dp(3), dp(4), dp(3), dp(8))
        })
    }

    private fun requestAi(task: String) {
        if (source.isBlank()) return showStatus("没有可发送的文字")
        val request = ++requestId
        val prompt = if (task == "continue" && generated.isNotBlank()) "$source\\n$generated" else source
        result.visibility = View.GONE
        showStatus("正在请求云端模型…（仅发送本次任务所需文字）")
        generator.transform(task, prompt) { response ->
            if (request != requestId) return@transform
            response.onSuccess {
                generated = it.trim()
                showResult(generated)
                if (task == "continue") showGeneratedActions()
                else showGeneratedActions()
            }.onFailure { showStatus(it.message ?: "处理失败，请检查云端配置和网络") }
        }
    }

    private fun showGeneratedActions() {
        section("使用结果")
        action("插入到光标位置") {
            if (generated.isNotBlank()) {
                onInsert(generated)
                showStatus("已插入；原有文字未被整段覆盖")
            }
        }
        action("替换选中文字") {
            if (!hasSelection) showStatus("请先选中文字，再重新打开文字工具")
            else if (onReplaceSelection(generated)) showStatus("已替换选中文字")
            else showStatus("替换失败，当前输入框不支持此操作")
        }
        action("继续生成") { requestAi("continue") }
    }

    private fun section(label: String) {
        body.addView(TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(palette.brand)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(3), dp(13), dp(3), dp(5))
        })
    }

    private fun action(label: String, run: () -> Unit) {
        body.addView(TextView(context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(palette.text)
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = rounded(palette.surface, 10)
            isClickable = true
            setOnClickListener { run() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
    }

    private fun showResult(value: String) {
        result.text = value
        result.visibility = View.VISIBLE
        showStatus("处理结果")
    }

    private fun showStatus(value: String) { status.text = value }

    private fun normalizeDate(value: String): String? {
        val patterns = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "yyyy年M月d日")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).apply { isLenient = false }.parse(value)
            }.getOrNull() ?: continue
            return SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(parsed) +
                "（" + SimpleDateFormat("EEEE", Locale.CHINA).format(parsed) + "）"
        }
        return null
    }

    private fun moneyToChinese(raw: String): String? = runCatching {
        val amount = BigDecimal(raw.replace(",", "").replace("￥", "").replace("¥", "").replace("元", "").trim())
        if (amount < BigDecimal.ZERO || amount > BigDecimal("999999999999.99")) return null
        val cents = amount.setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        val integer = cents / 100
        val fraction = (cents % 100).toInt()
        val digits = "零壹贰叁肆伍陆柒捌玖"
        val small = arrayOf("", "拾", "佰", "仟")
        val big = arrayOf("", "万", "亿", "兆")
        fun section(n: Int): String {
            if (n == 0) return ""
            var number = n
            var out = ""
            var zero = false
            for (i in 0..3) {
                val digit = number % 10
                if (digit == 0) zero = out.isNotEmpty()
                else {
                    if (zero) out = "零$out"
                    out = digits[digit].toString() + small[i] + out
                    zero = false
                }
                number /= 10
            }
            return out
        }
        var n = integer
        var group = 0
        var out = ""
        var pendingZero = false
        while (n > 0) {
            val part = (n % 10000).toInt()
            if (part == 0) pendingZero = out.isNotEmpty()
            else {
                val text = section(part) + big[group]
                out = text + (if (pendingZero || (part < 1000 && out.isNotEmpty())) "零" else "") + out
                pendingZero = false
            }
            n /= 10000
            group++
        }
        if (out.isEmpty()) out = "零"
        out += "元"
        if (fraction == 0) out + "整" else {
            val jiao = fraction / 10
            val fen = fraction % 10
            out + (if (jiao > 0) digits[jiao] + "角" else if (fen > 0) "零" else "") +
                (if (fen > 0) digits[fen] + "分" else "")
        }
    }.getOrNull()

    private fun formatCode(value: String): String {
        var depth = 0
        return value.lines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            if (line.startsWith("}") || line.startsWith(")") || line.startsWith("]")) depth = (depth - 1).coerceAtLeast(0)
            val formatted = "    ".repeat(depth) + line
            if (line.endsWith("{") || line.endsWith("(") || line.endsWith("[")) depth++
            formatted
        }.joinToString("\n")
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        val URL_REGEX = Regex("(https?://[^\\s<>]+|www\\.[^\\s<>]+)", RegexOption.IGNORE_CASE)
        val PHONE_REGEX = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")
        val EMAIL_REGEX = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val DATE_REGEX = Regex("(?:\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)")
        val MONEY_REGEX = Regex("(?:￥|¥)?\\s*\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?\\s*(?:元)?")
    }
}
