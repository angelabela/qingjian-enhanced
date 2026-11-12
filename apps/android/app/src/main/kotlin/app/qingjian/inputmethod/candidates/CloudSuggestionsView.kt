package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.qingjian.inputmethod.theme.Palette

/**
 * 云端联想独立行：只在有云端结果时显示，点击后用原始候选下标交回输入引擎。
 * 普通本地候选仍由 Rust 候选条绘制，不会被云端结果挤占位置。
 */
class CloudSuggestionsView(
    context: Context,
    private val palette: Palette,
    private val onSelect: (Int) -> Unit,
) : HorizontalScrollView(context) {
    private val density = resources.displayMetrics.density
    private val items = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        setBackgroundColor(palette.board)
        addView(items, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    fun show(candidates: List<Pair<Int, String>>) {
        items.removeAllViews()
        if (candidates.isEmpty()) {
            visibility = GONE
            return
        }
        candidates.forEach { (index, text) ->
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(palette.surface)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.faint)
            }
            val chip = TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(palette.cloud)
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                background = shape
                minHeight = (30 * density).toInt()
                isSingleLine = true
                setOnClickListener { onSelect(index) }
            }
            val params = LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply {
                marginEnd = (6 * density).toInt()
            }
            items.addView(chip, params)
        }
        visibility = VISIBLE
        scrollTo(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (38 * density).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
