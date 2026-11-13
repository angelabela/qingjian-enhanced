package app.qingjian.inputmethod.panel

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.qingjian.inputmethod.cloud.AiExpressionGroup
import app.qingjian.inputmethod.effects.GpuEffectBus
import app.qingjian.inputmethod.theme.Palette

/** 主动触发的多风格表达面板，与普通候选和云联想保持独立。 */
class AiExpressionPanel(
    context: Context,
    private val palette: Palette,
    headerHeight: Float,
    private val source: String,
    private val onGenerate: (String, (Result<List<AiExpressionGroup>>) -> Unit) -> Unit,
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
        setPadding(0, dp(18), 0, dp(18))
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
                text = "AI 情境表达"
                textSize = 17f
                setTextColor(palette.text)
                gravity = Gravity.CENTER_VERTICAL
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(context).apply {
                text = "DeepSeek"
                textSize = 11f
                setTextColor(palette.cloud)
                gravity = Gravity.CENTER
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, headerHeight.toInt().coerceAtLeast(dp(48))))
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(TextView(context).apply {
            text = "换一批表达  ↻"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(palette.brand, 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { pulse(this); generate() }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(14), dp(8), dp(14), dp(12))
        })
        content.addView(status)
        status.text = "正在理解上下文并生成多种表达…"
        generate()
    }

    private fun generate() {
        if (source.isBlank()) {
            status.text = "请先在输入框输入一段文字，再打开 AI 情境表达。"
            return
        }
        val request = ++requestNumber
        status.visibility = View.VISIBLE
        status.text = "正在生成…"
        clearGeneratedViews()
        undoAction = null
        onGenerate(source) { result ->
            if (request != requestNumber) return@onGenerate
            clearGeneratedViews()
            result.onSuccess { groups ->
                status.visibility = View.GONE
                groups.forEach { group ->
                    content.addView(TextView(context).apply {
                        text = group.title
                        textSize = 14f
                        setTextColor(palette.brand)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(dp(2), dp(14), 0, dp(7))
                    })
                    group.items.forEach { item ->
                        content.addView(TextView(context).apply {
                            text = item
                            textSize = 16f
                            setTextColor(palette.text)
                            setPadding(dp(13), dp(12), dp(13), dp(12))
                            background = rounded(palette.surface, 11)
                            isClickable = true
                            setOnClickListener {
                                pulse(this)
                                if (onReplace(item)) {
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
                pulse(this)
                if (onUndo()) {
                    android.widget.Toast.makeText(context, "已恢复替换前的内容", android.widget.Toast.LENGTH_SHORT).show()
                    content.removeView(this)
                    undoAction = null
                } else {
                    android.widget.Toast.makeText(context, "当前没有可撤销的替换", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        content.addView(undoAction, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(7)
        })
    }

    /** 第 0 个子项始终是状态栏；安全清理其余动态生成的结果项。 */
    private fun clearGeneratedViews() {
        while (content.childCount > 1) content.removeViewAt(1)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun pulse(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        GpuEffectBus.pulse(location[0] + view.width / 2f, location[1] + view.height / 2f, 0.68f)
    }

    private fun dp(value: Int) = (value * density).toInt()
}
