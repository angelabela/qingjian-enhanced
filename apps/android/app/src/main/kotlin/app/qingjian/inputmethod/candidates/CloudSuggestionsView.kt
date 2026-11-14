package app.qingjian.inputmethod.candidates

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.qingjian.inputmethod.theme.Palette

/**
 * 云端联想独立行：无胶囊、无色块。云图标与联想文字分开布局，
 * 点击整条结果上屏；普通候选栏继续由 Rust 单独绘制。
 */
class CloudSuggestionsView(
    context: Context,
    private val palette: Palette,
    private val onSelect: (Int) -> Unit,
) : HorizontalScrollView(context) {
    private val density = resources.displayMetrics.density
    private var smartSelection: ((String) -> Unit)? = null
    private var selectionAnimating = false
    private val items = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        // 云联想行与键盘底板使用同一主题背景色，视觉上保持整体一致。
        setBackgroundColor(palette.board)
        addView(items, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    fun showSmart(candidates: List<String>, onSelect: (String) -> Unit) {
        show(candidates.mapIndexed { index, value -> index to value })
        smartSelection = onSelect
    }

    fun show(candidates: List<Pair<Int, String>>) {
        smartSelection = null
        items.removeAllViews()
        if (candidates.isEmpty()) {
            visibility = GONE
            return
        }
        candidates.forEachIndexed { position, (index, suggestion) ->
            val result = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                // 只在不同联想结果之间留空，不绘制边框或背景。
                setPadding(
                    if (position == 0) 0 else (14 * density).toInt(),
                    0,
                    (10 * density).toInt(),
                    0,
                )
            }
            val cloudIcon = TextView(context).apply {
                text = "☁"
                textSize = 17f
                setTextColor(palette.cloud)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val label = TextView(context).apply {
                text = suggestion
                textSize = 17f
                setTextColor(palette.text)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                isSingleLine = true
                setPadding((5 * density).toInt(), 0, 0, 0)
            }
            result.addView(
                cloudIcon,
                LinearLayout.LayoutParams(
                    (22 * density).toInt(),
                    LayoutParams.WRAP_CONTENT,
                ),
            )
            result.addView(
                label,
                LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ),
            )
            result.alpha = 0f
            result.translationX = -24f * density
            result.translationY = 0f
            items.addView(
                result,
                LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT,
                ),
            )
            result.setOnClickListener {
                if (selectionAnimating) return@setOnClickListener
                val select = {
                    val smart = smartSelection
                    if (smart != null) smart(suggestion) else onSelect(index)
                }
                flyIntoEditorDirection(label, suggestion, select)
            }
            result.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(position * 24L)
                .setDuration(230L)
                .start()
        }
        visibility = VISIBLE
        scrollTo(0, 0)
    }


    /**
     * 点击后复制一层文字，从联想词位置飞向输入框所在的上方，再执行一次真实上屏回调。
     * IME 与宿主编辑器属于不同窗口，系统不允许普通 View 跨窗口绘制；动画到达输入法窗口上沿，
     * 随后只调用一次原有选择回调，不伪造编辑器坐标或重复提交文字。
     */
    private fun flyIntoEditorDirection(source: TextView, value: String, onFinished: () -> Unit) {
        val host = rootView as? ViewGroup
        if (host == null || host.width <= 0 || host.height <= 0 || !isAttachedToWindow) {
            onFinished()
            return
        }
        val sourceLocation = IntArray(2)
        val hostLocation = IntArray(2)
        source.getLocationOnScreen(sourceLocation)
        host.getLocationOnScreen(hostLocation)
        val flyer = TextView(context).apply {
            text = value
            textSize = source.textSize / resources.displayMetrics.scaledDensity
            setTextColor(palette.text)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            elevation = 12f * density
        }
        flyer.measure(
            MeasureSpec.makeMeasureSpec(host.width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(host.height, MeasureSpec.AT_MOST),
        )
        val width = flyer.measuredWidth.coerceAtLeast((24 * density).toInt())
        val height = flyer.measuredHeight.coerceAtLeast((20 * density).toInt())
        val startX = sourceLocation[0] - hostLocation[0] + source.width / 2 - width / 2
        val startY = sourceLocation[1] - hostLocation[1] + source.height / 2 - height / 2
        flyer.layout(startX, startY, startX + width, startY + height)
        selectionAnimating = true
        host.overlay.add(flyer)
        flyer.animate()
            .translationX((host.width / 2f) - (startX + width / 2f))
            .translationY(-height.toFloat() - startY)
            .alpha(0.12f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(220L)
            .withEndAction {
                host.overlay.remove(flyer)
                selectionAnimating = false
                if (isAttachedToWindow) onFinished()
            }
            .start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (44 * density).toInt()
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
