package app.qingjian.inputmethod.candidates

import android.content.Context
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.View
import android.graphics.Shader
import android.graphics.LinearGradient
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random
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
    private var dragCandidate = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var autoScrollAnimator: ValueAnimator? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var floatingDragArmed = false
    private var floatingDragActive = false
    private var floatingDownRawX = 0f
    private var floatingDownRawY = 0f
    private var floatingStartX = 0
    private var floatingStartY = 0
    private var tipsDetached = false
    private var anchorHeightBeforeFloat: Int? = null
    private var tipsAnchor: View? = null
    private var tipsDialog: Dialog? = null
    /** 当前编辑器光标屏幕坐标；云 Tips 始终锚定在输入位置上方。 */
    private var editorAnchorX: Float? = null
    private var editorAnchorY: Float? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { shatterAndHide() }
    private var shatterAnimator: ValueAnimator? = null
    private var shatterProgress = 0f
    private data class Shard(val x: Float, val y: Float, val size: Float, val angle: Float, val dx: Float, val dy: Float, val color: Int)
    private data class Glyph(val value: String, val x: Float, val y: Float, val dx: Float, val dy: Float, val rotation: Float, val size: Float, val color: Int)
    private var flyingGlyphs: List<Glyph> = emptyList()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shards: List<Shard> = emptyList()
    private val shardPath = Path()
    private val shardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val items = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        clipToOutline = true
        setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
        // 液态玻璃：多层半透明渐变、细亮边与高光；有悬浮窗权限时再启用系统背景模糊。
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            0x66F7FBFF, (palette.surface and 0x00FFFFFF) or 0x99000000.toInt(),
            (palette.surface and 0x00FFFFFF) or 0x88000000.toInt(),
        )).apply {
            cornerRadius = 22f * density
            setStroke((1f * density).toInt().coerceAtLeast(1), 0x88FFFFFF.toInt())
        }
        elevation = 18f * density
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        addView(items, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    fun setAnchorView(anchor: View) {
        tipsAnchor = anchor
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
        // 云联想始终只呈现最相关的一条，避免 Tips 变成第二条候选栏。
        candidates.take(1).forEachIndexed { position, (index, suggestion) ->
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
                text = arrayOf("✦", "☁", "✧", "◈", "❖")[Random.nextInt(5)]
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
                setPadding((9 * density).toInt(), 0, 0, 0)
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
                .setDuration(360L)
                .start()
        }
        val wasVisible = visibility == VISIBLE
        hideHandler.removeCallbacks(autoHideRunnable)
        shatterAnimator?.cancel()
        shatterProgress = 0f
        flyingGlyphs = emptyList()
        alpha = 1f
        animate().cancel()
        visibility = VISIBLE
        // 有悬浮窗权限时放入独立圆角 Window，使用系统背景模糊；输入法内只保留透明占位行。
        attachTipsDialogIfAllowed()

        if (!wasVisible) {
            alpha = 0f
            translationY = dragOffsetY - 8f * density
        }
        animate().alpha(1f).translationY(dragOffsetY).setDuration(320L).start()
        scrollTo(0, 0)
        startAutoScroll()
        scheduleAutoHide()
    }

    /** 保留宿主接口兼容；Tips 不再跟随编辑器光标，始终固定在输入法面板顶部。 */
    fun updateEditorAnchor(screenX: Float, screenY: Float) = Unit

    private fun attachTipsDialogIfAllowed() {
        if (tipsDialog?.isShowing == true || !Settings.canDrawOverlays(context)) return
        val anchor = tipsAnchor ?: return
        val oldParent = parent as? ViewGroup ?: return
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        anchorHeightBeforeFloat = anchor.layoutParams?.height
        anchor.layoutParams?.let { params ->
            params.height = 0
            anchor.layoutParams = params
        }
        oldParent.removeView(this)
        tipsDetached = false
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        runCatching {
            dialog.setContentView(this)
            dialog.setCancelable(false)
            dialog.window?.apply {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                )
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
                val glass = GradientDrawable().apply {
                    setColor(0xB3222D3D.toInt())
                    cornerRadius = 22f * density
                    setStroke((1f * density).toInt().coerceAtLeast(1), 0x99FFFFFF.toInt())
                }
                setBackgroundDrawable(glass)
            }
            dialog.show()
            dialog.window?.apply {
                setLayout(LayoutParams.WRAP_CONTENT, (46f * density).toInt())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setBackgroundBlurRadius((26f * density).toInt())
                }
                decorView.clipToOutline = true
                decorView.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 22f * density)
                    }
                }
                attributes = attributes.apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = ((resources.displayMetrics.widthPixels - decorView.width) / 2).coerceAtLeast(0)
                    y = location[1]
                }
                decorView.invalidateOutline()
                decorView.post {
                    if (tipsDialog === dialog && !tipsDetached) updateAnchorPosition()
                }
            }
            tipsDialog = dialog
        }.onFailure {
            runCatching { dialog.dismiss() }
            if (this.parent == null) {
                oldParent.addView(
                    this,
                    FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
                )
            }
        }
    }

    private fun updateAnchorPosition() {
        val dialog = tipsDialog ?: return
        val anchor = tipsAnchor ?: return
        if (!dialog.isShowing || tipsDetached) return
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        dialog.window?.let { window ->
            val width = window.decorView.width.takeIf { it > 0 } ?: measuredWidth
            window.attributes = window.attributes.apply {
                gravity = Gravity.TOP or Gravity.START
                x = ((resources.displayMetrics.widthPixels - width) / 2).coerceAtLeast(0)
                y = location[1]
            }
        }
    }

    private fun returnToAnchor() {
        val dialog = tipsDialog ?: return
        val anchor = tipsAnchor as? ViewGroup ?: return
        tipsDetached = false
        tipsDialog = null
        runCatching { dialog.dismiss() }
        anchorHeightBeforeFloat?.let { originalHeight ->
            anchor.layoutParams?.let { params ->
                params.height = originalHeight
                anchor.layoutParams = params
            }
        }
        anchorHeightBeforeFloat = null
        if (parent == null) {
            anchor.addView(
                this,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
        }
    }

    private fun scheduleAutoHide() {
        hideHandler.removeCallbacks(autoHideRunnable)
        hideHandler.postDelayed(autoHideRunnable, 5_000L)
    }

    private fun shatterAndHide() {
        if (visibility != VISIBLE || draggingTips) return
        stopAutoScroll()
        val random = Random(System.nanoTime())
        val hostLocation = IntArray(2)
        getLocationOnScreen(hostLocation)
        flyingGlyphs = buildList {
            for (i in 0 until items.childCount) {
                val row = items.getChildAt(i) as? ViewGroup ?: continue
                for (j in 0 until row.childCount) {
                    val label = row.getChildAt(j) as? TextView ?: continue
                    val value = label.text?.toString().orEmpty()
                    if (value.isEmpty()) continue
                    val location = IntArray(2)
                    label.getLocationOnScreen(location)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = label.typeface
                        textSize = label.textSize
                    }
                    var prefix = ""
                    value.forEach { character ->
                        val glyph = character.toString()
                        val glyphWidth = paint.measureText(glyph)
                        val x = location[0] - hostLocation[0] + label.paddingLeft + paint.measureText(prefix) + glyphWidth / 2f
                        val metrics = paint.fontMetrics
                        val y = location[1] - hostLocation[1] + label.height / 2f - (metrics.ascent + metrics.descent) / 2f
                        add(Glyph(
                            glyph, x, y,
                            (random.nextFloat() - 0.35f) * 110f * density,
                            (-12f - random.nextFloat() * 100f) * density,
                            (random.nextFloat() - 0.5f) * 70f,
                            label.textSize,
                            label.currentTextColor,
                        ))
                        prefix += character
                    }
                    label.alpha = 0f
                }
            }
        }
        shards = emptyList()
        shatterAnimator?.cancel()
        shatterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720L
            addUpdateListener {
                shatterProgress = it.animatedValue as Float
                alpha = 1f - shatterProgress
                invalidate()
            }
            start()
        }
        postDelayed({
            if (shatterProgress >= 0.99f) {
                visibility = GONE
                alpha = 1f
                shatterProgress = 0f
                shards = emptyList()
                flyingGlyphs = emptyList()
                returnToAnchor()
            }
        }, 740L)
    }

    private fun hideTips() {
        hideHandler.removeCallbacks(autoHideRunnable)
        shatterAnimator?.cancel()
        shatterProgress = 0f
        shards = emptyList()
        flyingGlyphs = emptyList()
        if (visibility != VISIBLE) return
        stopAutoScroll()
        animate().cancel()
        // 沿着输入框所在的上方方向收拢，边移动边渐隐，避免突然消失。
        animate()
            .alpha(0f)
            .translationY(dragOffsetY - height.coerceAtLeast((38 * density).toInt()).toFloat())
            .scaleX(0.92f)
            .scaleY(0.88f)
            .setDuration(360L)
            .withEndAction {
                visibility = GONE
                alpha = 1f
                translationY = dragOffsetY
                scaleX = 1f
                scaleY = 1f
                returnToAnchor()
            }
            .start()
    }

    /**
     * 点击后复制一层文字，从联想词位置飞向输入框所在的上方，再执行一次真实上屏回调。
     * IME 与宿主编辑器属于不同窗口，系统不允许普通 View 跨窗口绘制；动画在 Tips 窗口内完成，
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

    // 垂直/自由拖动整个 Tips 浮窗；吸附到键盘顶部附近时自动回到锚点。
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (tipsDialog?.isShowing != true) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                floatingDragArmed = true
                floatingDragActive = false
                floatingDownRawX = event.rawX
                floatingDownRawY = event.rawY
                val attrs = tipsDialog?.window?.attributes
                floatingStartX = attrs?.x ?: 0
                floatingStartY = attrs?.y ?: 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (floatingDragArmed && !floatingDragActive) {
                    val dx = event.rawX - floatingDownRawX
                    val dy = event.rawY - floatingDownRawY
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        floatingDragActive = true
                        draggingTips = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                floatingDragArmed = false
            }
        }
        return if (floatingDragActive) true else super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!floatingDragActive) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val window = tipsDialog?.window
                if (window != null) {
                    val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
                    window.attributes = window.attributes.apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = (floatingStartX + event.rawX - floatingDownRawX).toInt().coerceIn(0, maxX)
                        y = (floatingStartY + event.rawY - floatingDownRawY).toInt().coerceIn(0, maxY)
                    }
                    tipsDetached = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val anchor = tipsAnchor
                val window = tipsDialog?.window
                if (anchor != null && window != null) {
                    val location = IntArray(2)
                    anchor.getLocationOnScreen(location)
                    val targetX = ((resources.displayMetrics.widthPixels - window.decorView.width) / 2).coerceAtLeast(0)
                    val attrs = window.attributes
                    val snap = (48f * density).toInt()
                    if (kotlin.math.abs(attrs.x - targetX) <= snap && kotlin.math.abs(attrs.y - location[1]) <= snap) {
                        tipsDetached = false
                        window.attributes = attrs.apply { gravity = Gravity.TOP or Gravity.START; x = targetX; y = location[1] }
                    }
                }
                floatingDragActive = false
                floatingDragArmed = false
                draggingTips = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** 内容过长时自动往返滚动。 */
    private fun startAutoScroll() {
        stopAutoScroll()
        post {
            val distance = items.width - width
            if (distance <= (8 * density).toInt() || visibility != VISIBLE) return@post
            autoScrollAnimator = ValueAnimator.ofInt(0, distance).apply {
                duration = (distance * 22L).coerceIn(9000L, 26000L)
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = LinearInterpolator()
                addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }
    }

    private fun stopAutoScroll() {
        autoScrollAnimator?.cancel()
        autoScrollAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopAutoScroll()
        if (tipsDialog?.isShowing == true) {
            val dialog = tipsDialog
            tipsDialog = null
            runCatching { dialog?.dismiss() }
        }
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0) return
        post { updateAnchorPosition() }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (shatterProgress > 0f && flyingGlyphs.isNotEmpty()) {
            flyingGlyphs.forEach { glyph ->
                val progress = shatterProgress
                canvas.save()
                val x = glyph.x + glyph.dx * progress
                val y = glyph.y + glyph.dy * progress
                canvas.rotate(glyph.rotation * progress, x, y)
                glyphPaint.textSize = glyph.size
                glyphPaint.color = glyph.color
                glyphPaint.alpha = ((1f - progress) * 255f).toInt().coerceIn(0, 255)
                glyphPaint.style = Paint.Style.FILL
                canvas.drawText(glyph.value, x, y, glyphPaint)
                canvas.restore()
            }
        }
        if (shatterProgress > 0f && shards.isNotEmpty()) {
            shards.forEach { shard ->
                val travel = shatterProgress
                val cx = shard.x + shard.dx * travel
                val cy = shard.y + shard.dy * travel
                val radius = shard.size * (1f - travel * 0.28f)
                shardPath.reset()
                for (point in 0..2) {
                    val angle = shard.angle + point * (Math.PI * 2.0 / 3.0).toFloat()
                    val px = cx + kotlin.math.cos(angle) * radius
                    val py = cy + kotlin.math.sin(angle) * radius
                    if (point == 0) shardPath.moveTo(px, py) else shardPath.lineTo(px, py)
                }
                shardPath.close()
                shardPaint.color = shard.color
                shardPaint.alpha = ((1f - travel) * 220f).toInt()
                shardPaint.style = Paint.Style.FILL
                canvas.drawPath(shardPath, shardPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = (46 * density).toInt()
        val parentWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast((120 * density).toInt())
        val maxWidth = minOf(parentWidth, (resources.displayMetrics.widthPixels * 0.82f).toInt())
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
