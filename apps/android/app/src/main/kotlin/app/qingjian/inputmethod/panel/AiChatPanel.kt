package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.cloud.AiChatGenerator
import app.qingjian.inputmethod.theme.Palette

/** 轻量 AI 对话回复面板：布局与 AI 情境表达一致，直接展示分组回复，不占用输入框。 */
class AiChatPanel(
    context: Context,
    private val palette: Palette,
    headerHeight: Float,
    private val initialContext: String,
    private val generator: AiChatGenerator,
    private val onReplace: (String) -> Boolean,
    private val onUndo: () -> Boolean,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(12))
    }
    private val status = TextView(context).apply {
        textSize = 13f
        setTextColor(palette.gloss)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(18), dp(4), dp(18))
    }
    private var requestNumber = 0
    private var undoAction: TextView? = null

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
                gravity = Gravity.CENTER
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, headerHeight.toInt().coerceAtLeast(dp(48))))

        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(TextView(context).apply {
            text = "换一批回复  ↻"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(palette.brand, 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { generate() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(8), dp(14), dp(12))
        })

        content.addView(status)
        generate()
    }

    private fun generate() {
        if (initialContext.isBlank()) {
            status.visibility = View.VISIBLE
            status.text = "请先在输入框输入一段文字，再打开 AI 对话。"
            clearGeneratedViews()
            return
        }
        val request = ++requestNumber
        status.visibility = View.VISIBLE
        status.text = "正在理解上下文并生成回复…"
        clearGeneratedViews()
        undoAction = null
        generator.suggestReplies(initialContext) { result ->
            if (request != requestNumber) return@suggestReplies
            clearGeneratedViews()
            result.onSuccess { replies ->
                status.visibility = View.GONE
                val styles = listOf("简洁自然", "温柔体贴", "轻松幽默", "俏皮可爱", "成熟得体")
                replies.chunked(3).forEachIndexed { groupIndex, groupReplies ->
                    content.addView(TextView(context).apply {
                        text = styles.getOrElse(groupIndex) { "其他回复" }
                        textSize = 14f
                        setTextColor(palette.brand)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(dp(2), dp(14), 0, dp(7))
                    })
                    groupReplies.forEach { reply ->
                        content.addView(TextView(context).apply {
                            text = reply
                            textSize = 16f
                            setTextColor(palette.text)
                            setPadding(dp(13), dp(12), dp(13), dp(12))
                            background = rounded(palette.surface, 11)
                            isClickable = true
                            setOnClickListener {
                                if (onReplace(reply)) {
                                    showUndoAction()
                                    android.widget.Toast.makeText(context, "已替换输入框内容", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "替换失败：当前输入框不支持替换全文", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(6)
                        })
                    }
                }
            }.onFailure {
                status.visibility = View.VISIBLE
                status.text = it.message ?: "生成失败，请检查网络和云端配置"
            }
        }
    }

    private fun showUndoAction() {
        if (undoAction != null) return
        undoAction = TextView(context).apply {
            text = "↶ 撤销本次替换"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(palette.brand)
            background = rounded(palette.surface, 11)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener {
                if (onUndo()) {
                    android.widget.Toast.makeText(context, "已恢复替换前的内容", android.widget.Toast.LENGTH_SHORT).show()
                    content.removeView(this)
                    undoAction = null
                }
            }
        }
        content.addView(undoAction, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        })
    }

    /** 第 0 个子项始终是状态栏；安全清理其余动态生成的结果项。 */
    private fun clearGeneratedViews() {
        while (content.childCount > 1) content.removeViewAt(content.childCount - 1)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun dp(value: Int) = (value * density).toInt()
}
