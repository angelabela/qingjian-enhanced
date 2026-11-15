package app.qingjian.inputmethod.candidates

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.LinearGradient
import android.view.MotionEvent
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.qingjian.inputmethod.theme.Palette

/**
 * 云端联想独立浮动 Tips：不占用候选栏高度，有结果时轻量出现、无结果时淡出；
 * 点击联想词上屏，普通候选栏继续由 Rust 单独绘制。
 */
class CloudSuggestionsView(
    context: Context,
    private val palette: Palette,
    private val onSelect: (Int) -> Unit,
) : HorizontalScrollView(context) {
    private val density = resources.displayMetrics.density
    private var smartSelection: ((String) -> Unit)? = null
    private var selectionAnimating = false
    private var draggingTips = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private val edgeFadeWidth = (22 * density).toFloat()
    private val leftFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rightFadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dragHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.text and 0x66FFFFFF
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val items = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
        // 小型悬浮提示：采用主题面板底色与细边框，不再铺满输入法宽度。
        background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = 18f * density
            setStroke((1f * density).toInt().coerceAtLeast(1), palette.faint)
        }
        elevation = 8f * density
        addView(items, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        setOnTouchListener { _, event ->
            // 顶部窄条作为拖动把手，其余区域仍保留原生横向滑动与候选点击。
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingTips = event.y <= 11f * density
                    if (draggingTips) {
                        dragStartRawX = event.rawX
                        dragStartRawY = event.rawY
                        dragStartTranslationX = translationX
                        dragStartTranslationY = translationY
                        true
                    } else false
                }
                MotionEvent.ACTION_MOVE -> if (draggingTips) {
                    translationX = dragStartTranslationX + event.rawX - dragStartRawX
                    translationY = dragStartTranslationY + event.rawY - dragStartRawY
                    true
                } else false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = draggingTips
                    draggingTips = false
                    wasDragging
                }
                else -> draggingTips
            }
        }
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
            hideTips()
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
                textSize = 15f
                setTextColor(palette.cloud)
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val label = TextView(context).apply {
                text = suggestion
                textSize = 14f
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
        val wasVisible = visibility == VISIBLE
        animate().cancel()
        visibility = VISIBLE
        if (!wasVisible) {
            alpha = 0f
            translationY = -8f * density
        }
        animate().alpha(1f).translationY(0f).setDuration(190L).start()
        scrollTo(0, 0)
    }


    private fun hideTips() {
        if (visibility != VISIBLE) return
        animate().cancel()
        // 沿着输入框所在的上方方向收拢，边移动边渐隐，避免突然消失。
        animate()
            .alpha(0f)
            .translationY(-height.coerceAtLeast((38 * density).toInt()).toFloat())
            .scaleX(0.92f)
            .scaleY(0.88f)
            .setDuration(230L)
            .withEndAction {
                visibility = GONE
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
            }
            .start()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        val transparent = palette.surface and 0x00FFFFFF
        leftFadePaint.shader = LinearGradient(
            0f, 0f, edgeFadeWidth, 0f,
            intArrayOf(palette.surface, transparent),
            null, Shader.TileMode.CLAMP,
        )
        rightFadePaint.shader = LinearGradient(
            w - edgeFadeWidth, 0f, w.toFloat(), 0f,
            intArrayOf(transparent, palette.surface),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // 固定在 Tips 可视窗口边缘绘制渐隐层，不随内部文字一起横向滚动。
        canvas.drawRect(0f, 0f, edgeFadeWidth, height.toFloat(), leftFadePaint)
        canvas.drawRect(width - edgeFadeWidth, 0f, width.toFloat(), height.toFloat(), rightFadePaint)
        val center = width / 2f
        canvas.drawLine(center - 8f * density, 5f * density, center + 8f * density, 5f * density, dragHandlePaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (38 * density).toInt()
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast((120 * density).toInt())
        val maxWidth = minOf(parentWidth, (resources.displayMetrics.widthPixels * 0.82f).toInt())
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
