package app.qingjian.inputmethod.candidates

import android.content.Context
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import android.view.VelocityTracker
import android.view.animation.OvershootInterpolator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
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
    private var floatingVelocityTracker: VelocityTracker? = null
    private var tipSide = 1
    private var tipYRatio = 0.35f
    private var hideInProgress = false
    private var tipsDetached = false
    private var anchorHeightBeforeFloat: Int? = null
    private var tipsAnchor: View? = null
    private var tipsDialog: Dialog? = null
    private var tipsContainer: FrameLayout? = null
    private val positionPrefs = context.getSharedPreferences("cloud_tips_position", Context.MODE_PRIVATE)
    /** 当前编辑器光标屏幕坐标；云 Tips 始终锚定在输入位置上方。 */
    private var editorAnchorX: Float? = null
    private var editorAnchorY: Float? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { shatterAndHide() }
    private var shatterAnimator: ValueAnimator? = null
    private var shatterProgress = 0f
    private data class Shard(val x: Float, val y: Float, val size: Float, val angle: Float, val dx: Float, val dy: Float, val color: Int)
    private data class Glyph(val value: String, val x: Float, val y: Float, val dx: Float, val dy: Float, val rotation: Float, val size: Float, val color: Int, val delay: Float)
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
        // 主题色磨砂玻璃：独立窗口负责绘制底板，内容层保持透明。
        // 上层内容透明；磨砂玻璃由 Dialog 内部的下层容器单独绘制。
        background = ColorDrawable(Color.TRANSPARENT)
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
                hideHandler.removeCallbacks(autoHideRunnable)
                val select = {
                    val smart = smartSelection
                    if (smart != null) smart(suggestion) else onSelect(index)
                }
                flyIntoEditorDirection(label, suggestion) {
                    // 先收起旧 Tips，再回调选择；若新候选立即到达，不会被旧动画误隐藏。
                    hideTips()
                    select()
                }
            }
            animateGlyphEntrance(label, suggestion)
            result.animate()
                .alpha(1f)
                .translationX(0f)
                .setStartDelay(position * 24L)
                .setDuration(360L)
                .start()
        }
        val wasVisible = visibility == VISIBLE
        tipsContainer?.visibility = VISIBLE
        hideHandler.removeCallbacks(autoHideRunnable)
        shatterAnimator?.cancel()
        shatterProgress = 0f
        flyingGlyphs = emptyList()
        val motion = tipsContainer ?: this
        motion.alpha = 1f
        motion.animate().cancel()
        visibility = VISIBLE
        // 有悬浮窗权限时放入独立圆角 Window，使用系统背景模糊；输入法内只保留透明占位行。
        attachTipsDialogIfAllowed()

        if (!wasVisible) {
            motion.alpha = 0f
            motion.translationY = dragOffsetY
            motion.scaleX = 0.72f
            motion.scaleY = 0.72f
            motion.pivotX = if (tipSide == 0) 0f else motion.width.toFloat()
            motion.pivotY = motion.height / 2f
        }
        motion.animate().alpha(1f).translationY(dragOffsetY).scaleX(1f).scaleY(1f)
            .setDuration(420L).setInterpolator(OvershootInterpolator(1.15f)).start()
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
        anchorHeightBeforeFloat = anchor.layoutParams?.height
        anchor.layoutParams?.let { params -> params.height = 0; anchor.layoutParams = params }
        oldParent.removeView(this)
        tipsDetached = true

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val blurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled
        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 16f * density
                setColor(if (blurAvailable) 0x80FFFFFF.toInt() else 0xE6FFFFFF.toInt())
                setStroke((1f * density).toInt().coerceAtLeast(1), 0x66FFFFFF)
            }
            clipToOutline = true
            elevation = 18f * density
            addView(this@CloudSuggestionsView, FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER,
            ))
        }
        tipsContainer = container
        val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        runCatching {
            dialog.setContentView(container)
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
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.show()
            dialog.window?.apply {
                setLayout(LayoutParams.WRAP_CONTENT, (46f * density).toInt())
                if (blurAvailable) setBackgroundBlurRadius((40f * density).toInt())
                decorView.setBackgroundColor(Color.TRANSPARENT)
                (decorView as? ViewGroup)?.clipChildren = false
                decorView.post {
                    if (tipsDialog !== dialog) return@post
                    val window = dialog.window ?: return@post
                    val viewWidth = decorView.width.takeIf { it > 0 } ?: container.measuredWidth
                    val viewHeight = decorView.height.takeIf { it > 0 } ?: container.measuredHeight
                    val screenW = resources.displayMetrics.widthPixels
                    val screenH = resources.displayMetrics.heightPixels
                    val margin = (12f * density).toInt()
                    val minY = (40f * density).toInt()
                    val maxY = (screenH - viewHeight - 80f * density).toInt().coerceAtLeast(minY)
                    tipSide = positionPrefs.getInt("side", 1).coerceIn(0, 1)
                    tipYRatio = positionPrefs.getFloat("yRatio", 0.35f).coerceIn(0f, 1f)
                    val x = if (tipSide == 0) margin else (screenW - viewWidth - margin).coerceAtLeast(0)
                    val y = (minY + (maxY - minY) * tipYRatio).toInt().coerceIn(minY, maxY)
                    window.attributes = window.attributes.apply {
                        gravity = Gravity.TOP or Gravity.START
                        this.x = x
                        this.y = y
                    }
                }
            }
            tipsDialog = dialog
        }.onFailure {
            tipsContainer = null
            tipsDetached = false
            runCatching { dialog.dismiss() }
            (this.parent as? ViewGroup)?.removeView(this)
            background = ColorDrawable(Color.TRANSPARENT)
            oldParent.addView(
                this,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
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
                y = (location[1] - (52f * density).toInt()).coerceAtLeast(0)
            }
        }
    }

    private fun returnToAnchor() {
        val dialog = tipsDialog ?: return
        val anchor = tipsAnchor as? ViewGroup ?: return
        tipsDetached = false
        tipsDialog = null
        tipsContainer = null
        runCatching { dialog.dismiss() }
        anchorHeightBeforeFloat?.let { originalHeight ->
            anchor.layoutParams?.let { params ->
                params.height = originalHeight
                anchor.layoutParams = params
            }
        }
        anchorHeightBeforeFloat = null
        if (parent == null) {
            background = ColorDrawable(Color.TRANSPARENT)
            anchor.addView(
                this,
                FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
            )
        }
    }

    private fun scheduleAutoHide() {
        hideHandler.removeCallbacks(autoHideRunnable)
        hideHandler.postDelayed(autoHideRunnable, 4_000L)
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
                    val codePoints = value.codePoints().toArray()
                    codePoints.forEachIndexed { glyphIndex, codePoint ->
                        val glyph = String(Character.toChars(codePoint))
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
                            (glyphIndex * 0.065f).coerceAtMost(0.72f),
                        ))
                        prefix += glyph
                    }
                    label.alpha = 0f
                }
            }
        }
        shards = emptyList()
        shatterAnimator?.cancel()
        val motionView = tipsContainer ?: this
        shatterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_050L
            addUpdateListener {
                shatterProgress = it.animatedValue as Float
                // Tips 玻璃底板与文字一起向上、向侧面漂移并缩小；字符则各自延迟散开。
                motionView.alpha = 1f - shatterProgress
                motionView.translationX = dragOffsetX + 12f * density * shatterProgress
                motionView.translationY = dragOffsetY - 10f * density * shatterProgress
                motionView.scaleX = 1f - 0.10f * shatterProgress
                motionView.scaleY = 1f - 0.10f * shatterProgress
                invalidate()
            }
            start()
        }
        postDelayed({
            if (shatterProgress >= 0.99f) {
                visibility = GONE
                tipsContainer?.visibility = GONE
                val motion = tipsContainer ?: this
                motion.alpha = 1f
                motion.translationX = dragOffsetX
                motion.translationY = dragOffsetY
                motion.scaleX = 1f
                motion.scaleY = 1f
                shatterProgress = 0f
                shards = emptyList()
                flyingGlyphs = emptyList()
                returnToAnchor()
            }
        }, 1_080L)
    }

    private fun hideTips() {
        hideHandler.removeCallbacks(autoHideRunnable)
        shatterAnimator?.cancel()
        shatterProgress = 0f
        shards = emptyList()
        flyingGlyphs = emptyList()
        if (visibility != VISIBLE) return
        stopAutoScroll()
        val motion = tipsContainer ?: this
        motion.animate().cancel()
        // 沿着输入框所在的上方方向收拢，边移动边渐隐，避免突然消失。
        motion.animate()
            .alpha(0f)
            .translationY(dragOffsetY - height.coerceAtLeast((38 * density).toInt()).toFloat())
            .scaleX(0.92f)
            .scaleY(0.88f)
            .setDuration(360L)
            .withEndAction {
                visibility = GONE
                tipsContainer?.visibility = GONE
                val motion = tipsContainer ?: this
                motion.alpha = 1f
                motion.translationX = dragOffsetX
                motion.translationY = dragOffsetY
                motion.scaleX = 1f
                motion.scaleY = 1f
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
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = source.typeface
            textSize = source.textSize
        }
        val glyphs = value.codePoints().toArray().map { String(Character.toChars(it)) }
        if (glyphs.isEmpty()) {
            onFinished()
            return
        }
        selectionAnimating = true
        // 点击时整块玻璃 Tips 也缓慢收拢，字符按顺序从卡片位置飞向输入框方向。
        val motion = tipsContainer ?: this
        motion.animate().cancel()
        motion.animate()
            .alpha(0.24f)
            .scaleX(0.95f)
            .scaleY(0.90f)
            .translationY(dragOffsetY - 7f * density)
            .setDuration(1_450L)
            .start()
        var cursorX = sourceLocation[0] - hostLocation[0] + source.width / 2f -
            paint.measureText(value) / 2f
        val startY = sourceLocation[1] - hostLocation[1] + source.height / 2f
        var remaining = glyphs.size
        glyphs.forEachIndexed { index, glyph ->
            val glyphWidth = paint.measureText(glyph).coerceAtLeast(8f * density)
            val flyer = TextView(context).apply {
                text = glyph
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
            val w = flyer.measuredWidth.coerceAtLeast(glyphWidth.toInt())
            val h = flyer.measuredHeight.coerceAtLeast((20 * density).toInt())
            val left = cursorX + (glyphWidth - w) / 2f
            val top = startY - h / 2f
            flyer.layout(left.toInt(), top.toInt(), left.toInt() + w, top.toInt() + h)
            host.overlay.add(flyer)
            val targetX = host.width / 2f + (index - (glyphs.size - 1) / 2f) * glyphWidth * 0.72f
            flyer.animate()
                .translationX(targetX - left)
                .translationY(-startY - h * 0.8f)
                .alpha(0.08f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setStartDelay(index * 125L)
                .setDuration(950L)
                .withEndAction {
                    host.overlay.remove(flyer)
                    remaining--
                    if (remaining == 0) {
                        selectionAnimating = false
                        if (isAttachedToWindow) onFinished()
                    }
                }
                .start()
            cursorX += glyphWidth
        }
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
                    val targetY = (location[1] - (52f * density).toInt()).coerceAtLeast(0)
                    val attrs = window.attributes
                    val snap = (48f * density).toInt()
                    if (kotlin.math.abs(attrs.x - targetX) <= snap && kotlin.math.abs(attrs.y - targetY) <= snap) {
                        tipsDetached = false
                        window.attributes = attrs.apply { gravity = Gravity.TOP or Gravity.START; x = targetX; y = targetY }
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    tipsDialog?.window?.attributes?.let { attrs ->
                        positionPrefs.edit().putInt("x", attrs.x).putInt("y", attrs.y).apply()
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
                val progress = ((shatterProgress - glyph.delay) / (1f - glyph.delay)).coerceIn(0f, 1f)
                if (progress <= 0f) return@forEach
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
        val maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            .coerceAtLeast((120 * density).toInt())
        // 先按内容自然宽度测量，再限制到屏幕宽度；短联想短显示，长联想才启用横向滚动。
        items.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
        val desiredWidth = (items.measuredWidth + paddingLeft + paddingRight)
            .coerceAtLeast((76 * density).toInt())
            .coerceAtMost(maxWidth)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
