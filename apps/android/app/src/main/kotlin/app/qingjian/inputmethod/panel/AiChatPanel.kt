package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.text.Editable
import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import app.qingjian.inputmethod.panel.editor.FloatingEditor
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.cloud.AiChatGenerator
import app.qingjian.inputmethod.theme.Palette

/** 独立 AI 对话面板：智能回复与多轮助手共用用户明确提供的上下文。 */
class AiChatPanel(
    context: Context,
    private val palette: Palette,
    headerHeight: Float,
    private val initialContext: String,
    private val generator: AiChatGenerator,
    private val onReplace: (String) -> Boolean,
    private val onUndo: () -> Boolean,
    private val onClose: () -> Unit,
) : LinearLayout(context), FloatingEditor {
    private val density = resources.displayMetrics.density
    private val conversation = mutableListOf<Pair<String, String>>()
    private var busy = false
    private var activeInput: EditText? = null

    /** Route the existing IME keyboard into whichever field the user selected. */
    override val connection: InputConnection = object : BaseInputConnection(this, true) {
        override fun getEditable(): Editable = activeInput?.text
            ?: android.text.SpannableStringBuilder()
    }

    override val enterLabel: String get() = if (mode == Mode.ASSISTANT) "发送" else "完成"

    override fun onKey(keyCode: Int) {
        val field = activeInput ?: return
        val editable = field.text
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(0)
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (start != end) editable.delete(minOf(start, end), maxOf(start, end))
                else if (start > 0) editable.delete(Character.offsetByCodePoints(editable, start, -1), start)
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (field === prompt) sendAssistantMessage()
                else editable.insert(start, "\n")
            }
        }
    }
    private var mode = Mode.REPLIES
    private enum class Mode { REPLIES, ASSISTANT }

    private val contextInput = EditText(context).apply {
        setText(initialContext)
        hint = "粘贴聊天记录或输入对话上下文（可编辑）"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isFocusable = true
        isFocusableInTouchMode = true
        textSize = 14f
        setTextColor(palette.text)
        setHintTextColor(palette.gloss)
        gravity = Gravity.TOP
        minLines = 3
        maxLines = 6
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(palette.surface, 12)
    }
    private val resultArea = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(0, dp(8), 0, dp(8))
    }
    private val status = TextView(context).apply {
        textSize = 12f
        setTextColor(palette.gloss)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }
    private val transcript = LinearLayout(context).apply {
        orientation = VERTICAL
    }
    private lateinit var sendButton: TextView
    private val prompt = EditText(context).apply {
        hint = "输入你想问 AI 的内容"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isFocusable = true
        isFocusableInTouchMode = true
        textSize = 14f
        setTextColor(palette.text)
        setHintTextColor(palette.gloss)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(palette.surface, 12)
        minLines = 1
        maxLines = 3
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.board)
        activeInput = contextInput
        contextInput.setOnClickListener { focusInput(contextInput) }
        prompt.setOnClickListener { focusInput(prompt) }
        contextInput.setOnFocusChangeListener { _, focused -> if (focused) activeInput = contextInput }
        prompt.setOnFocusChangeListener { _, focused -> if (focused) activeInput = prompt }
        addView(header(headerHeight))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
            addView(actionText("智能回复", true) { switchMode(Mode.REPLIES) }, LayoutParams(0, dp(38), 1f))
            addView(actionText("AI 助手", false) { switchMode(Mode.ASSISTANT) }, LayoutParams(0, dp(38), 1f))
        })
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(8))
            addView(TextView(context).apply {
                text = "聊天上下文"
                textSize = 13f
                setTextColor(palette.gloss)
                setPadding(0, dp(4), 0, dp(6))
            })
            addView(contextInput, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "仅在你点击生成或发送时，才会把上方内容发送到已配置的云端模型。请勿粘贴密码等敏感信息。"
                textSize = 11f
                setTextColor(palette.gloss)
                setPadding(dp(2), dp(5), dp(2), dp(5))
            })
            addView(status)
            addView(resultArea)
            addView(transcript)
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
            addView(prompt, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            sendButton = actionText("发送", true) { sendAssistantMessage() }
            addView(sendButton, LayoutParams(dp(66), dp(44)))
        })
        renderMode()
    }

    private fun header(height: Float) = LinearLayout(context).apply {
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
            text = "AI 对话"
            textSize = 17f
            setTextColor(palette.text)
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(TextView(context).apply {
            text = "云端模型"
            textSize = 11f
            setTextColor(palette.cloud)
        })
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, height.toInt().coerceAtLeast(dp(48)))
    }

    private fun actionText(label: String, primary: Boolean, click: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else palette.text)
        background = rounded(if (primary) palette.brand else palette.surface, 12)
        isClickable = true
        setOnClickListener { click() }
    }

    private fun switchMode(next: Mode) {
        if (busy) return
        mode = next
        renderMode()
    }

    private fun renderMode() {
        val row = (getChildAt(1) as? LinearLayout) ?: return
        (row.getChildAt(0) as? TextView)?.apply {
            background = rounded(if (mode == Mode.REPLIES) palette.brand else palette.surface, 12)
            setTextColor(if (mode == Mode.REPLIES) Color.WHITE else palette.text)
        }
        (row.getChildAt(1) as? TextView)?.apply {
            background = rounded(if (mode == Mode.ASSISTANT) palette.brand else palette.surface, 12)
            setTextColor(if (mode == Mode.ASSISTANT) Color.WHITE else palette.text)
        }
        contextInput.visibility = View.VISIBLE
        resultArea.visibility = if (mode == Mode.REPLIES) View.VISIBLE else View.GONE
        transcript.visibility = if (mode == Mode.ASSISTANT) View.VISIBLE else View.GONE
        prompt.visibility = if (mode == Mode.ASSISTANT) View.VISIBLE else View.GONE
        sendButton.visibility = if (mode == Mode.ASSISTANT) View.VISIBLE else View.GONE
        status.text = if (mode == Mode.REPLIES) "每种风格生成 3 条，共 15 条；点击即可替换输入框内容" else "AI 助手支持在当前面板内连续多轮交流"
        if (mode == Mode.REPLIES && resultArea.childCount == 0) {
            resultArea.addView(actionText("生成 15 条回复（每种风格 3 条）", true) { generateReplies() },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        }
    }

    private fun focusInput(field: EditText) {
        activeInput = field
        field.requestFocus()
        field.post { field.setSelection(field.text.length) }
    }

    private fun generateReplies() {
        if (busy) return
        val source = contextInput.text.toString().trim()
        if (source.isBlank()) {
            status.text = "请先粘贴或输入聊天上下文"
            return
        }
        busy = true
        status.text = "正在生成回复…"
        resultArea.removeAllViews()
        generator.suggestReplies(source) { result ->
            busy = false
            resultArea.removeAllViews()
            result.onSuccess { replies ->
                status.text = "点击回复将替换输入框原内容；可随时撤销恢复"
                replies.forEachIndexed { index, reply ->
                    val styles = listOf("简洁自然", "温柔体贴", "轻松幽默", "俏皮可爱", "成熟得体")
                    val card = TextView(context).apply {
                        text = "${styles.getOrElse(index / 3) { "自然表达" }} · ${index % 3 + 1}\n$reply"
                        textSize = 15f
                        setTextColor(palette.text)
                        setPadding(dp(13), dp(12), dp(13), dp(12))
                        background = rounded(palette.surface, 12)
                        isClickable = true
                        setOnClickListener { if (onReplace(reply)) { status.text = "已替换输入框内容，可点击下方撤销恢复原文"; showUndoAction() } else { status.text = "替换失败：当前输入框不支持读取或替换全文" } }
                    }
                    resultArea.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(7)
                    })
                }
                resultArea.addView(actionText("重新生成 15 条回复", false) { generateReplies() },
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
            }.onFailure {
                status.text = it.message ?: "生成失败，请检查网络和云端配置"
                resultArea.addView(actionText("重试", false) { generateReplies() },
                    LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
            }
        }
    }

    private fun showUndoAction() {
        val existing = resultArea.findViewWithTag<View>("ai-reply-undo")
        if (existing != null) return
        val undo = actionText("↶ 撤销本次替换，恢复原内容", false) {
            if (onUndo()) {
                status.text = "已恢复替换前的输入内容"
                resultArea.findViewWithTag<View>("ai-reply-undo")?.let(resultArea::removeView)
            } else {
                status.text = "撤销失败：没有可恢复的内容"
            }
        }.apply { tag = "ai-reply-undo" }
        resultArea.addView(undo, 0, LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
    }

    private fun sendAssistantMessage() {
        if (busy) return
        val text = prompt.text.toString().trim()
        if (text.isBlank()) return
        prompt.setText("")
        if (conversation.isEmpty() && contextInput.text.isNotBlank()) {
            conversation += "user" to "以下是用户提供的聊天上下文，请仅将其作为背景理解，不要直接复述：\\n${contextInput.text.toString().trim().takeLast(4000)}"
        }
        conversation += "user" to text
        appendBubble("我", text, user = true)
        busy = true
        status.text = "AI 正在思考…"
        generator.chat(conversation.toList()) { result ->
            busy = false
            result.onSuccess { answer ->
                conversation += "assistant" to answer
                while (conversation.size > 12) conversation.removeAt(0)
                appendBubble("AI", answer, user = false)
                status.text = "可以继续提问"
            }.onFailure {
                conversation.removeLastOrNull()
                status.text = it.message ?: "请求失败，请重试"
            }
        }
    }

    private fun appendBubble(label: String, text: String, user: Boolean) {
        transcript.addView(TextView(context).apply {
            this.text = "$label\n$text"
            textSize = 14f
            setTextColor(palette.text)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(if (user) palette.surface else palette.functionalKey, 12)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        })
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radius).toFloat()
        setColor(color)
    }

    private fun dp(value: Int) = (value * density).toInt()
}
