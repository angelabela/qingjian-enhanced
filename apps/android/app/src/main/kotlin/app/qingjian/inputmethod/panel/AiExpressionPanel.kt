package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import app.qingjian.inputmethod.theme.Palette

/** AI 智能表达面板：默认基于光标附近少量上下文生成五条候选。 */
class AiExpressionPanel(
    context: Context,
    private val palette: Palette,
    private val actions: Actions,
    initialText: String,
) : LinearLayout(context) {
    interface Actions {
        fun generate(input: String, tone: String, contextText: String?, done: (List<String>?, String?) -> Unit)
        fun insert(text: String)
        fun close()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun label(text: String, size: Float = 14f) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(palette.text)
    }
    private val input = EditText(context).apply {
        setText(initialText)
        textSize = 16f
        setTextColor(palette.text)
        setHintTextColor(palette.gloss)
        hint = "输入一个词或一句话"
        minLines = 2
        maxLines = 4
        gravity = Gravity.TOP
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(palette.board, dp(12))
    }
    private val contextCheck = CheckBox(context).apply {
        text = "结合光标附近上下文（最多 120 字）"
        textSize = 12f
        setTextColor(palette.gloss)
        isChecked = true
    }
    private val tone = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            listOf("自然", "正式", "幽默", "委婉", "亲切", "简洁"))
    }
    private val results = LinearLayout(context).apply { orientation = VERTICAL }
    private val progress = ProgressBar(context).apply { visibility = View.GONE }
    private val generate = Button(context).apply { text = "生成 5 条表达" }

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
        setBackgroundColor(palette.board)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = HORIZONTAL
            addView(label("AI 智能表达", 18f), LayoutParams(0, dp(48), 1f))
            addView(Button(context).apply {
                text = "返回"
                setOnClickListener { actions.close() }
            })
        }
        addView(header)
        addView(label("把简短想法变成自然、完整的表达", 12f))
        addView(input, LayoutParams(LayoutParams.MATCH_PARENT, dp(84)).apply { topMargin = dp(10) })
        addView(contextCheck)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("语气", 13f))
            addView(tone, LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
            addView(progress, LayoutParams(dp(28), dp(28)))
        }
        addView(row)
        generate.isAllCaps = false
        generate.setOnClickListener { request() }
        addView(generate, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(results)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        // 打开面板即尝试生成；空输入时等待用户输入。
        if (initialText.isNotBlank()) post { request() }
    }

    private fun request() {
        val value = input.text.toString().trim()
        if (value.isEmpty()) {
            input.error = "先输入一个词或一句话"
            return
        }
        generate.isEnabled = false
        progress.visibility = View.VISIBLE
        results.removeAllViews()
        results.addView(label("正在生成，请稍候…", 13f))
        val tones = listOf("自然", "正式", "幽默", "委婉", "亲切", "简洁")
        val selectedTone = tones.getOrElse(tone.selectedItemPosition) { "自然" }
        val contextText = if (contextCheck.isChecked) actionsContext() else null
        actions.generate(value, selectedTone, contextText) { items, error ->
            generate.isEnabled = true
            progress.visibility = View.GONE
            results.removeAllViews()
            if (items.isNullOrEmpty()) {
                results.addView(label(error ?: "暂时没有生成结果，请重试", 13f))
            } else {
                items.take(5).forEachIndexed { index, text ->
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(8))
                    background = rounded(palette.surface, dp(12))
                }
                card.addView(label("表达 ${index + 1}", 11f).apply { setTextColor(palette.gloss) })
                card.addView(label(text, 15f).apply { setPadding(0, dp(5), 0, dp(8)) })
                val insert = Button(context).apply {
                    this.text = "插入输入框"
                    isAllCaps = false
                    setOnClickListener { actions.insert(text) }
                }
                card.addView(insert)
                    results.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    })
                }
            }
        }
    }

    private fun actionsContext(): String? = actionsContextProvider?.invoke()

    private var actionsContextProvider: (() -> String?)? = null

    fun setContextProvider(provider: () -> String?) { actionsContextProvider = provider }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), palette.faint)
    }
}
