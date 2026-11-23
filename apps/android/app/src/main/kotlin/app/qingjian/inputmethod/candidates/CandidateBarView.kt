package app.qingjian.inputmethod.candidates

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import kotlin.math.sin
import app.qingjian.inputmethod.R
import app.qingjian.inputmethod.core.frame.Candidate
import app.qingjian.inputmethod.keyboard.Haptics
import app.qingjian.inputmethod.panel.base.PanelIcons
import app.qingjian.inputmethod.popup.ChoicePopup
import app.qingjian.inputmethod.settings.ToolbarTool
import app.qingjian.inputmethod.settings.Settings
import app.qingjian.inputmethod.theme.Palette

/**
 * 键盘上方那一条，三种样子：组句中是候选（[CandidateStrip]，位图来自 [source]，横向滑动、长按弹译词气泡），
 * 平时是工具栏（[Toolbar]，中间那排可横滑），调高度时是拖动手柄（[ResizeHandle]）。
 */
@SuppressLint("ViewConstructor")
class CandidateBarView(
    context: Context,
    private val palette: Palette,
    /** 候选字号档的倍数：调大时条跟着高，候选不被截。 */
    textScale: Float,
    private val source: StripSource,
    /** 工具栏中间那排放的工具（用户定制的）。 */
    tools: List<ToolbarTool>,
    private val listener: Listener,
    fontWeight: Settings.FontWeight = Settings.FontWeight.NORMAL,
    private var transparentBackground: Boolean = false,
    private val candidateTextStyles: CandidateTextStyleEngine.Config = CandidateTextStyleEngine.Config.defaults(palette.text, fontWeight),
) : View(context) {
    interface Listener {
        fun onSelect(index: Int)

        /** 候选是电话/短信关键词或手机号时，交给服务展示快捷动作。 */
        fun onSmartAction(text: String, action: String)

        /** 点了拼音行：光标挪到第 [index] 个字之后。 */
        fun onMoveCaret(index: Int)

        /** 长按气泡里选了译词：上屏第 [index] 个候选的第 [sense] 条译文。 */
        fun onSelectTranslation(index: Int, sense: Int)

        /** 长按气泡里选了「删除」：删第 [index] 个候选（用户词、学习记录）。 */
        fun onForget(index: Int)

        /** 工具栏中间那排点了一个工具；调高度在这里自己处理，不会传出去。 */
        fun onToolbar(tool: ToolbarTool)

        /** 工具栏左端的 logo：进功能面板。 */
        fun openFunctions()

        /** 工具栏右端：收起键盘。 */
        fun hideKeyboard()

        /** 拖动调高度：[deltaPx] 是手指相对按下处往上走的距离，[done] 表示手指抬起。 */
        fun onResizeDrag(deltaPx: Float, done: Boolean)

        /** 点了候选右端的 ⌄：展开全部候选（[CandidateGrid]）。 */
        fun onExpand()
    }

    private val density = resources.displayMetrics.density
    private var adaptiveVideoTextColor: Int? = null
    // 给候选字、拼音与译文留出更充足的垂直空间；大字档按比例继续增高，避免位图被候选栏裁切。
    val barHeight = 66f * density * maxOf(1f, textScale)
    private val strip = CandidateStrip(
        density, barHeight, textScale, palette.gloss, fontWeight, transparentBackground,
        palette.brand, palette.text, candidateTextStyles, fallbackBackgroundColor = palette.board,
    )
    private val toolbar = Toolbar(context, palette, density, barHeight, tools)
    private val resizeHandle = ResizeHandle(
        palette, density, barHeight,
        onDrag = listener::onResizeDrag,
        onDone = { resizing = false },
    )

    private var resizing = false
        set(value) {
            field = value
            invalidate()
        }

    // 防止同一候选快照被上游重复回调时再次关闭弹层、重置滚动并触发位图重绘。
    private var lastShownPreedit: String? = null
    private var lastShownCandidates: List<Candidate>? = null

    private val popup = ChoicePopup(this, palette)

    private val divider = Paint().apply {
        color = palette.faint
        strokeWidth = maxOf(1f, density * 0.7f)
    }

    /** 候选右端 ⌄ 那一格的宽；候选滚到它底下为止。 */
    private val expandSlot = 46f * density
    private val expandFill = Paint()
    private val expandIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.text }
    private val smartActionBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.brand }
    private val smartActionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.onBrand
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    /** 拼音组句时强调首候选：低透明脉冲底光 + 细描边，不遮挡候选文字。 */
    private val firstCandidateGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.brand
        style = Paint.Style.FILL
    }
    private val firstCandidateRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.brand
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val firstCandidateSheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * density
    }
    private var activePulseIndex = -1
    private var activePulseStartedAt = 0L
    private val pulseMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }

    private val expandable: Boolean get() = strip.candidates.any { !it.cloud }

    /** 动态背景切换时统一设置候选文字颜色；传 null 会恢复主题/候选样式自身的颜色。 */
    fun setAdaptiveTextColor(color: Int?) {
        if (adaptiveVideoTextColor == color) return
        adaptiveVideoTextColor = color
        strip.setAdaptiveTextColor(color)
        invalidate()
    }

    /** 视频背景帧亮度变化时，自动切换候选文字的高对比颜色。 */
    fun setVideoBackgroundLuminance(luminance: Float) {
        val current = adaptiveVideoTextColor
        val next = when (current) {
            android.graphics.Color.BLACK -> if (luminance < 0.44f) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            android.graphics.Color.WHITE -> if (luminance > 0.58f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            else -> if (luminance >= 0.52f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        setAdaptiveTextColor(next)
    }

    /** 显示的是工具栏：横滑滑的是它中间那排图标，不是整条。 */
    /** 工具栏位置上的一句提示与点它要做的事；null 是没有提示。几秒后、出候选或点过就收起。 */
    private var notice: Pair<String, () -> Unit>? = null
    /** 验证码/剪贴板等主动提示短暂覆盖候选栏，避免下一帧候选刷新将提示立即清掉。 */
    private var noticeOverridesCandidates = false
    private val clearNotice = Runnable {
        notice = null
        noticeOverridesCandidates = false
        invalidate()
    }

    private val toolbarShown: Boolean get() = !resizing && (noticeOverridesCandidates || strip.isEmpty && notice == null)

    /** 候选能用的宽度：有 ⌄ 时让出那一格。 */
    private val stripWidth: Int get() = if (expandable) width - expandSlot.toInt() else width

    /** 气泡对着的候选下标；气泡开着时高亮跟到它上面。 */
    private var popupIndex = -1
    /** 空格左右滑动时临时预选的候选；抬手提交，滑动过程只更新高亮。 */
    private var gestureSelectionIndex = -1

    /** 气泡里「删除」那一项的下标；这个候选删不了时为 -1。 */
    private var forgetItem = -1

    /** 气泡里只有「删除」（候选没有译文）。 */
    private var forgetOnly = false

    private val scroller = OverScroller(context)

    /** 这一下按在拼音行上：左右拖是挪光标，不滚候选、不弹译词。 */
    private var onPreedit = false

    /** 拖动中光标上次挪到的位置（拼音行第几个字之后）；还没挪过为 -1。 */
    private var dragCaret = -1
    /** 拼音行可见插入光标；新一轮拼音默认落在末尾，用户拖动后跟随手指。 */
    private var visibleCaretIndex = -1

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (onPreedit) {
                dragCaret(e2.x)
                return true
            }
            if (toolbarShown) {
                toolbar.scrollTo(toolbar.offset + dx, width)
                invalidate()
                return true
            }
            scrollTo((scrollX + dx).toInt().coerceIn(0, maxScroll()), 0)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (onPreedit) return true
            if (toolbarShown) {
                scroller.fling(toolbar.offset.toInt(), 0, -vx.toInt(), 0, 0, toolbar.maxOffset(width).toInt(), 0, 0)
            } else {
                scroller.fling(scrollX, 0, -vx.toInt(), 0, 0, maxScroll(), 0, 0)
            }
            postInvalidateOnAnimation()
            return true
        }

        override fun onLongPress(e: MotionEvent) = openTranslations(e)

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            tap(e.x, e.y)
            return true
        }
    })

    init {
        setBackgroundColor(if (transparentBackground) android.graphics.Color.TRANSPARENT else palette.surface)
    }

    /** 拼音行右侧的提示（删候选的结果）由渲染器从 Rust 那边的帧里取，这里只要拼音和候选。 */
    fun show(preedit: String, candidates: List<Candidate>, animateCandidates: Boolean = true) {
        if (lastShownPreedit == preedit && lastShownCandidates == candidates) return
        val preeditChanged = lastShownPreedit != preedit
        if (preedit.isEmpty()) visibleCaretIndex = -1
        else if (preeditChanged || visibleCaretIndex < 0) visibleCaretIndex = preedit.length
        else visibleCaretIndex = visibleCaretIndex.coerceIn(0, preedit.length)
        val preserveScroll = lastShownPreedit == preedit && lastShownCandidates != null
        val previousScroll = scrollX
        // 新候选帧到来时重置高亮跟踪；后续手势选中变化由 drawCandidatePulse 实时跟随。
        activePulseIndex = -1
        activePulseStartedAt = 0L
        lastShownPreedit = preedit
        lastShownCandidates = candidates.toList()
        gestureSelectionIndex = -1
        closePopup()
        // 只有用户真正开始组合输入时才清除提示；空 preedit 状态下的候选帧刷新不能吞掉提示。
        if (preedit.isNotEmpty()) clearNotice.run()
        strip.show(preedit, candidates, animateCandidates)
        scroller.forceFinished(true)
        if (!preserveScroll) scrollTo(0, 0)
        repaint()
        if (preserveScroll) scrollTo(previousScroll.coerceIn(0, maxScroll()), 0)
    }

    /** 空格横滑预选候选；不重绘 Rust 位图，只更新独立选中层。 */
    fun setGestureSelection(index: Int) {
        val next = index.takeIf { it in strip.candidates.indices } ?: -1
        if (gestureSelectionIndex == next) return
        gestureSelectionIndex = next
        if (next >= 0) scrollCandidateIntoView(next)
        invalidate()
    }

    /**
     * 空格横滑预选到屏幕外的候选时，自动滚动候选条把该词完整带入视口。
     * 左右两侧都预留与绘制一致的内边距，避免末尾候选只露出半截。
     */
    private fun scrollCandidateIntoView(index: Int) {
        val rect = strip.wordRect(index) ?: return
        val inset = strip.leftInsetPx(stripWidth)
        val safeLeft = scrollX + inset
        val safeRight = scrollX + stripWidth - 8f * density
        val target = when {
            rect.left + inset < safeLeft -> rect.left.toInt()
            rect.right + inset > safeRight -> (rect.right + inset - stripWidth + 8f * density).toInt()
            else -> return
        }
        scroller.forceFinished(true)
        scrollTo(target.coerceIn(0, maxScroll()), 0)
    }

    /** 提交或取消空格横滑预选。 */
    fun finishGestureSelection(commit: Boolean) {
        val selected = gestureSelectionIndex
        gestureSelectionIndex = -1
        invalidate()
        if (commit && selected in strip.candidates.indices) listener.onSelect(selected)
    }

    /** 在工具栏的位置放一句提示 [message]，点它执行 [action]（打开对应的设置页）。 */
    fun showNotice(message: String, action: () -> Unit) {
        notice = message to action
        noticeOverridesCandidates = false
        removeCallbacks(clearNotice)
        postDelayed(clearNotice, NOTICE_MS)
        invalidate()
    }

    /** 当前是否仍显示指定内容的持久提示；提示被输入动作清除后允许同一内容再次显示。 */
    fun hasPersistentNotice(message: String? = null): Boolean =
        noticeOverridesCandidates && notice != null && (message == null || notice?.first == message)

    /** 短信验证码/剪贴板等主动提示默认持续显示；可为扫码快捷动作单独指定自动收起时间。 */
    fun showPersistentNotice(message: String, autoDismissMs: Long? = null, action: () -> Unit) {
        notice = message to action
        noticeOverridesCandidates = true
        removeCallbacks(clearNotice)
        if (autoDismissMs != null && autoDismissMs > 0L) postDelayed(clearNotice, autoDismissMs)
        invalidate()
    }

    /** 动态背景开关变化时同步更新候选条底色及 Rust 位图透明处理。 */
    fun setTransparentBackground(enabled: Boolean, backgroundColor: Int) {
        if (transparentBackground != enabled) {
            transparentBackground = enabled
            strip.setTransparentBackground(enabled)
        }
        setBackgroundColor(if (enabled) android.graphics.Color.TRANSPARENT else backgroundColor)
        invalidate()
    }

    /** 没有可用剪贴板内容时立即收起旧提示，避免展示过期验证码/文本。 */
    fun clearNotice() {
        removeCallbacks(clearNotice)
        if (notice != null) {
            notice = null
            noticeOverridesCandidates = false
            invalidate()
        }
    }

    /** 进入拖动调高度（功能面板里点「键盘高度」）。 */
    fun startResize() {
        resizing = true
    }

    /** 按当前高亮重画位图：新的一帧到了、或长按气泡换了对着的候选。 */
    private fun repaint() {
        // 旧 Rust 选中底色会残留在位图里；选中态统一由上层液态玻璃绘制。
        strip.paint(if (strip.isEmpty) null else source.render(-1, palette.dark, stripWidth))
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barHeight.toInt())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            if (toolbarShown) toolbar.scrollTo(scroller.currX.toFloat(), width) else scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) = when {
        resizing -> resizeHandle.draw(canvas, width)
        notice != null && (noticeOverridesCandidates || strip.isEmpty) ->
            toolbar.drawNotice(canvas, width, notice!!.first)
        strip.isEmpty -> {
            toolbar.draw(canvas, width)
            postInvalidateOnAnimation()
        }
        else -> {
            val activeHighlightIndex = when {
                gestureSelectionIndex in strip.candidates.indices -> gestureSelectionIndex
                popupIndex in strip.candidates.indices -> popupIndex
                else -> 0
            }
            strip.draw(canvas, scrollX, stripWidth)
            // 选中候选与首选共用同一套呼吸光效；有选中项时首选光效自动让位。
            drawCandidatePulse(canvas, activeHighlightIndex)
            if (strip.advanceTextFade()) postInvalidateOnAnimation()
            if (expandable) drawExpand(canvas)
            // 视频背景模式下候选栏与键盘共用同一动态画面，不绘制底部分界线。
            if (!transparentBackground) {
                canvas.drawRect(scrollX.toFloat(), height - divider.strokeWidth, scrollX + width.toFloat(), height.toFloat(), divider)
            }
            Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = when {
        resizing -> resizeHandle.onTouch(event, width)
        popup.showing -> onPopupTouch(event)
        else -> {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onPreedit = !strip.isEmpty && strip.caretAt(event.x - strip.leftInsetPx(stripWidth), event.y) >= 0
                dragCaret = -1
                // 长按一触发 GestureDetector 就不再给拖动；按在拼音行上先停一停再拖也要跟
                gestures.setIsLongpressEnabled(!onPreedit)
            }
            // 拼音光标拖动不再只依赖 GestureDetector 的 onScroll 阈值回调；
            // 直接消费每一帧 ACTION_MOVE，避免悬浮层/父容器分发导致拖动时光标不更新。
            if (onPreedit && event.actionMasked == MotionEvent.ACTION_MOVE) {
                dragCaret(event.x)
            }
            val handled = gestures.onTouchEvent(event)
            if (onPreedit && (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)) {
                onPreedit = false
                dragCaret = -1
                gestures.setIsLongpressEnabled(true)
            }
            handled || onPreedit
        }
    }

    /** 首选或当前选中词及其释义共用同一套呼吸高亮。 */
    private fun drawCandidatePulse(canvas: Canvas, index: Int) {
        if (index !in strip.candidates.indices) return
        val now = android.os.SystemClock.uptimeMillis()
        // 高亮框的动画状态绑定当前候选下标；空格手势滑到下一个词时，特效框立即跟随。
        if (activePulseIndex != index) {
            activePulseIndex = index
            activePulseStartedAt = now
        }
        val elapsed = now - activePulseStartedAt
        val candidateTextMaxWidth = (stripWidth - strip.leftInsetPx(stripWidth)).coerceAtLeast(1f) / 3f
        val rect = strip.candidateEffectRect(index, candidateTextMaxWidth) ?: return
        // View Canvas 已由 scrollX 平移；候选与高亮框统一使用内容坐标，再补左内边距。
        rect.offset(strip.leftInsetPx(stripWidth) + strip.candidateRevealOffsetPx(index), 0f)
        rect.top = rect.top.coerceAtLeast(3f * density)
        rect.bottom = rect.bottom.coerceAtMost(height - 3f * density)
        if (rect.right <= scrollX || rect.left >= scrollX + stripWidth) return
        val phase = (elapsed % FIRST_CANDIDATE_PULSE_PERIOD_MS).toFloat() /
            FIRST_CANDIDATE_PULSE_PERIOD_MS
        val breath = (0.5f + 0.5f * sin(phase * (Math.PI * 2.0).toFloat()))
        val layerBounds = android.graphics.RectF(scrollX.toFloat(), 0f, (scrollX + stripWidth).toFloat(), height.toFloat())
        val layer = canvas.saveLayer(layerBounds, null)
        canvas.clipRect(scrollX.toFloat(), 0f, (scrollX + stripWidth).toFloat(), height.toFloat())
        val glow = android.graphics.RectF(rect).apply { inset(-4f * density, -3f * density) }
        val accent = palette.brand
        firstCandidateGlow.shader = android.graphics.LinearGradient(
            glow.left, glow.top, glow.right, glow.bottom,
            intArrayOf((accent and 0x00FFFFFF) or 0x26303030, (accent and 0x00FFFFFF) or 0x50303030, (accent and 0x00FFFFFF) or 0x20303030),
            floatArrayOf(0f, 0.52f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        firstCandidateGlow.alpha = (175f + breath * 70f).toInt()
        canvas.drawRoundRect(glow, 11f * density, 11f * density, firstCandidateGlow)
        firstCandidateGlow.shader = null
        val outline = android.graphics.RectF(rect).apply { inset(-1.2f * density, -1f * density) }
        firstCandidateRing.color = accent
        firstCandidateRing.alpha = (135f + breath * 105f).toInt()
        canvas.drawRoundRect(outline, 9f * density, 9f * density, firstCandidateRing)
        firstCandidateSheen.shader = android.graphics.LinearGradient(
            rect.left, rect.top, rect.right, rect.top,
            intArrayOf(0x00FFFFFF, 0xBFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, (0.25f + 0.5f * breath).coerceIn(0f, 1f), 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        val shineY = rect.top + 2f * density
        canvas.drawLine(rect.left + 5f * density, shineY, rect.right - 5f * density, shineY, firstCandidateSheen)
        firstCandidateSheen.shader = null

        // 选中框也套用候选文字相同的左右渐隐遮罩，滑到边缘时不会留下完整发光框。
        val leftEdge = scrollX + strip.leftInsetPx(stripWidth)
        val rightEdge = scrollX + stripWidth
        val fadeWidth = minOf(30f * density, stripWidth.toFloat()).coerceAtLeast(1f)
        pulseMaskPaint.shader = android.graphics.LinearGradient(
            leftEdge, 0f, leftEdge + fadeWidth, 0f,
            intArrayOf(0x00000000, 0xFF000000.toInt()),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(scrollX.toFloat(), 0f, rightEdge.toFloat(), height.toFloat(), pulseMaskPaint)
        pulseMaskPaint.shader = android.graphics.LinearGradient(
            rightEdge - fadeWidth, 0f, rightEdge.toFloat(), 0f,
            intArrayOf(0xFF000000.toInt(), 0x00000000),
            floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(scrollX.toFloat(), 0f, rightEdge.toFloat(), height.toFloat(), pulseMaskPaint)
        pulseMaskPaint.shader = null
        canvas.restoreToCount(layer)
        postInvalidateOnAnimation()
    }

    /** 候选格右上角的小电话/短信入口；普通候选文字区域仍照常点击上屏。 */
    private fun smartActions(text: String): List<String> {
        val phone = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)").containsMatchIn(text)
        return when {
            phone -> listOf("phone", "sms")
            text.contains("电话") -> listOf("phone")
            text.contains("短信") -> listOf("sms")
            else -> emptyList()
        }
    }

    private fun smartActionAt(index: Int, text: String, contentX: Float, y: Float): String? {
        val actions = smartActions(text)
        if (actions.isEmpty()) return null
        val rect = strip.wordRect(index)?.apply { offset(strip.leftInsetPx(stripWidth), 0f) } ?: return null
        // 快捷按钮固定在候选格底部的独立操作行，不再压在候选文字上。
        val cy = rect.bottom - 9f * density
        if (y !in (cy - 9f * density)..(cy + 9f * density)) return null
        actions.forEachIndexed { i, action ->
            val cx = rect.right - (11f + i * 21f) * density
            if (contentX in (cx - 9f * density)..(cx + 9f * density)) return action
        }
        return null
    }

    private fun drawSmartActions(canvas: Canvas) {
        strip.candidates.forEachIndexed { index, candidate ->
            val actions = smartActions(candidate.text)
            val rect = strip.wordRect(index)?.apply { offset(strip.leftInsetPx(stripWidth), 0f) } ?: return@forEachIndexed
            actions.forEachIndexed { i, action ->
                val cx = rect.right - (11f + i * 21f) * density - scrollX
                val cy = rect.bottom - 9f * density
                canvas.drawRoundRect(
                    cx - 9f * density, cy - 8f * density,
                    cx + 9f * density, cy + 8f * density,
                    5f * density, 5f * density, smartActionBg,
                )
                canvas.drawText(if (action == "phone") "☎" else "✉", cx, cy + 4f * density, smartActionText)
            }
        }
    }

    /** 钉在右端的 ⌄：与候选行同一高度居中，左边一道短竖线隔开。 */
    private fun drawExpand(canvas: Canvas) {
        val left = scrollX + stripWidth.toFloat()
        if (!transparentBackground) {
            expandFill.color = strip.background
            canvas.drawRect(left, 0f, scrollX + width.toFloat(), height.toFloat(), expandFill)
        }
        val cy = (strip.candidatesTop + height) / 2f
        canvas.drawRect(left, cy - 12 * density, left + divider.strokeWidth, cy + 12 * density, divider)
        PanelIcons.draw(canvas, PanelIcons.Icon.EXPAND, left + expandSlot / 2, cy, 24 * density, expandIcon)
    }

    /**
     * 沿拼音行拖：光标跟着手指落到最近的字缝，每过一格轻震一下（#881）。
     * 拼音行按视图坐标画、不随候选滚动；新的一帧到了缝的位置会变，所以每次按手指重新找。
     */
    private fun dragCaret(x: Float) {
        val caret = strip.caretNear(x - strip.leftInsetPx(stripWidth))
        if (caret < 0 || caret == dragCaret) return
        dragCaret = caret
        visibleCaretIndex = caret
        invalidate()
        Haptics.tap(context)
        listener.onMoveCaret(caret)
    }

    private fun tap(x: Float, y: Float) {
        // 主动提示显示期间，点击候选栏任意区域都执行提示动作，而不是误选被临时覆盖的候选。
        if (notice != null && noticeOverridesCandidates) {
            val action = notice!!.second
            clearNotice.run()
            action()
            return
        }
        if (expandable && x >= stripWidth) {
            listener.onExpand()
            return
        }
        // 拼音行钉在视口上，不随候选滚动，按视图坐标查
        val caret = strip.caretAt(x - strip.leftInsetPx(stripWidth), y)
        if (caret >= 0) {
            visibleCaretIndex = caret
            invalidate()
            listener.onMoveCaret(caret)
            return
        }
        if (!strip.isEmpty) {
            strip.indexAt(x + scrollX - strip.leftInsetPx(stripWidth)).takeIf { it >= 0 }?.let { index ->
                val text = strip.candidates.getOrNull(index)?.text.orEmpty()
                listener.onSelect(index)
            }
            return
        }
        notice?.let { (_, action) ->
            clearNotice.run()
            action()
            return
        }
        when {
            toolbar.onLogo(x) -> listener.openFunctions()
            toolbar.onHide(x, width) -> listener.hideKeyboard()
            else -> when (val tool = toolbar.toolAt(x, width)) {
                null -> Unit
                ToolbarTool.RESIZE -> resizing = true
                else -> listener.onToolbar(tool)
            }
        }
    }

    /**
     * 长按候选：弹出它的译词，第一条对着手指；删得掉的候选（用户词、学过的词）末尾再跟一项「删除」，
     * 对应桌面端修饰键 + 数字。没有译文也删不了的候选不弹。
     */
    private fun openTranslations(event: MotionEvent) {
        if (event.x >= stripWidth) return
        val index = strip.indexAt(event.x + scrollX - strip.leftInsetPx(stripWidth))
        val candidate = strip.candidates.getOrNull(index) ?: return
        val senses = candidate.senses
        if (senses.isEmpty() && !candidate.forgettable) return
        popupIndex = index
        repaint()
        Haptics.hold(context)
        val items = senses.map { sense ->
            ChoicePopup.Item(sense.text, listOfNotNull(sense.pos, sense.reading).joinToString(" "), sense.fresh)
        } + if (candidate.forgettable) listOf(ChoicePopup.Item(context.getString(R.string.candidate_forget), context.getString(R.string.candidate_forget_note))) else emptyList()
        forgetItem = if (candidate.forgettable) senses.size else -1
        forgetOnly = senses.isEmpty()
        // 只有「删除」时不预选，得滑上去才算，免得长按一松手就删了
        popup.show(items, initial = if (senses.isEmpty()) -1 else 0, fingerScreenX = event.rawX, bottom = 0f)
    }

    /** 气泡开着：横向移动只用来选项，抬手上屏；往下滑进键盘算取消。 */
    private fun onPopupTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                // 只有「删除」时手指得往上滑进气泡才算选中
                popup.track(event.rawX, cancelled = event.y > height + 24 * density || (forgetOnly && event.y > 0))

            MotionEvent.ACTION_UP -> {
                val index = popupIndex
                val forget = forgetItem
                closePopup()?.let { item ->
                    if (item == forget) listener.onForget(index) else listener.onSelectTranslation(index, item)
                }
            }

            MotionEvent.ACTION_CANCEL -> closePopup()
        }
        return true
    }

    private fun closePopup(): Int? {
        val sense = if (popup.showing) popup.finish() else null
        // 气泡对着的不是第一个候选时，高亮要回到第一个
        val moved = popupIndex > 0
        popupIndex = -1
        if (moved) repaint()
        return sense
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closePopup()
    }

    // 内容绘制从左内边距开始，因此最大滚动量也要补偿这段内边距。
    private fun maxScroll(): Int =
        (strip.contentWidth + strip.leftInsetPx(stripWidth) - stripWidth).toInt().coerceAtLeast(0)

    private companion object {
        const val NOTICE_MS = 5000L
        const val FIRST_CANDIDATE_PULSE_PERIOD_MS = 620L
    }
}
